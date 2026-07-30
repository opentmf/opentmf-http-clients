package org.opentmf.client.starter.registry;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.opentmf.client.common.model.AuthType;
import org.opentmf.client.common.model.ClientProperties;
import org.opentmf.client.common.model.ClientType;
import org.opentmf.client.common.resilience.ResilienceRegistries;
import org.opentmf.client.reactive.service.api.TokenService;
import org.opentmf.client.reactive.util.WebClientConfigUtil;
import org.opentmf.client.rest.service.api.SyncTokenService;
import org.opentmf.client.starter.ApachePoolMeters;
import org.opentmf.client.starter.reactive.ReactiveClientRegistrar;
import org.opentmf.client.starter.rest.RestClientRegistrar;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.resources.ConnectionProvider;

/**
 * Registry-style lifecycle API for clients built programmatically at runtime (e.g. from catalog
 * rows), complementing the create-once Spring beans of the static
 * {@code opentmf.http-clients.*} path. Supports hot-swap ({@link #replace}) and removal
 * ({@link #evict}); a retired client is closed only after a grace period, so requests already
 * in flight on it can finish.
 *
 * <p>Close semantics per client type: Apache — graceful close of the pooled connection manager
 * (via the request factory's {@code DisposableBean}); JDK — guarded {@code AutoCloseable} close,
 * effective on Java 21+ runtimes and a GC-reclaimed no-op on 17; Netty — graceful
 * {@code ConnectionProvider.disposeLater()} of the client's (and its token client's) pools.</p>
 *
 * <p>Replacing or evicting a name also resets its resilience4j instances, so a re-created client
 * starts with fresh state built from its current properties.</p>
 */
@Slf4j
public class HttpClientRegistry implements DisposableBean {

  /**
   * A dynamically managed synchronous client: both views of the same underlying HTTP client,
   * plus the token service matching its auth configuration.
   */
  public record ManagedSyncClient(RestTemplate restTemplate, RestClient restClient,
      SyncTokenService tokenService) {}

  /**
   * A dynamically managed reactive client and the token service matching its auth configuration.
   */
  public record ManagedReactiveClient(WebClient webClient, TokenService tokenService) {}

  private record Entry(ClientType type, Object managed, Runnable closer) {}

  private record RetiringClient(String name, Runnable closer) {}

  private final @Nullable RestClientRegistrar restRegistrar;
  private final @Nullable ReactiveClientRegistrar reactiveRegistrar;
  private final ObjectProvider<ResilienceRegistries> resilienceRegistriesProvider;
  private final ObjectProvider<ApachePoolMeters> poolMetersProvider;
  private final Duration closeGracePeriod;
  private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
  private final Set<RetiringClient> retiring = ConcurrentHashMap.newKeySet();
  private final ScheduledExecutorService closeScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
    var thread = new Thread(runnable, "opentmf-client-close");
    thread.setDaemon(true);
    return thread;
  });

  public HttpClientRegistry(@Nullable RestClientRegistrar restRegistrar,
      @Nullable ReactiveClientRegistrar reactiveRegistrar,
      ObjectProvider<ResilienceRegistries> resilienceRegistriesProvider,
      ObjectProvider<ApachePoolMeters> poolMetersProvider,
      Duration closeGracePeriod) {
    this.restRegistrar = restRegistrar;
    this.reactiveRegistrar = reactiveRegistrar;
    this.resilienceRegistriesProvider = resilienceRegistriesProvider;
    this.poolMetersProvider = poolMetersProvider;
    this.closeGracePeriod = closeGracePeriod;
  }

  /**
   * Returns the client registered under {@code name}, creating it when absent. The existing
   * client is returned as-is even if {@code properties} differ — use {@link #replace} to apply
   * changed properties.
   */
  public ManagedSyncClient getOrCreate(String name, ClientType type, ClientProperties properties) {
    requireSync(name, type);
    var entry = entries.computeIfAbsent(name, n -> buildSyncEntry(n, type, properties));
    return syncClient(name, entry);
  }

  /**
   * Builds a new client from the given properties, swaps it in under {@code name}, and closes
   * the previous client (if any) after the grace period. Its resilience instances are reset
   * first, so the new client runs with resilience state built from the new properties.
   */
  public ManagedSyncClient replace(String name, ClientType type, ClientProperties properties) {
    requireSync(name, type);
    resetResilience(name);
    var entry = buildSyncEntry(name, type, properties);
    var previous = entries.put(name, entry);
    scheduleClose(name, previous);
    return syncClient(name, entry);
  }

  /**
   * Reactive counterpart of {@link #getOrCreate(String, ClientType, ClientProperties)}.
   */
  public ManagedReactiveClient getOrCreateReactive(String name, ClientProperties properties) {
    requireReactive(name);
    var entry = entries.computeIfAbsent(name, n -> buildReactiveEntry(n, properties));
    return reactiveClient(name, entry);
  }

  /**
   * Reactive counterpart of {@link #replace(String, ClientType, ClientProperties)}.
   */
  public ManagedReactiveClient replaceReactive(String name, ClientProperties properties) {
    requireReactive(name);
    resetResilience(name);
    var entry = buildReactiveEntry(name, properties);
    var previous = entries.put(name, entry);
    scheduleClose(name, previous);
    return reactiveClient(name, entry);
  }

  /**
   * Removes the client registered under {@code name}: its pool gauges disappear immediately,
   * its resilience instances are reset, and the client itself closes after the grace period.
   *
   * @return true when a client was registered under the name
   */
  public boolean evict(String name) {
    var entry = entries.remove(name);
    if (entry == null) {
      return false;
    }
    var poolMeters = poolMetersProvider.getIfAvailable();
    if (poolMeters != null) {
      poolMeters.deregister(name);
    }
    resetResilience(name);
    scheduleClose(name, entry);
    return true;
  }

  /**
   * Names of all currently registered clients.
   */
  public Set<String> names() {
    return Set.copyOf(entries.keySet());
  }

  @Override
  public void destroy() {
    // Application shutdown: close retiring clients still inside their grace period (the
    // scheduler's own queued tasks refuse to run after shutdown, hence the explicit set)
    // plus every live client, immediately.
    closeScheduler.shutdownNow();
    List.copyOf(retiring).forEach(this::closeRetiring);
    entries.forEach((name, entry) -> runCloser(name, entry.closer()));
    entries.clear();
  }

  private Entry buildSyncEntry(String name, ClientType type, ClientProperties properties) {
    var restTemplate = restRegistrar.createRestTemplate(name, type, properties);
    var requestFactory = rawRequestFactory(restTemplate);
    var restClient = restRegistrar.createRestClient(restTemplate);
    var tokenService = restRegistrar.createTokenService(restClient, properties);
    var managed = new ManagedSyncClient(restTemplate, restClient, tokenService);
    Runnable closer = () -> closeRequestFactory(name, requestFactory);
    return new Entry(type, managed, closer);
  }

  /**
   * Captures the underlying (closeable) request factory. When interceptors are present,
   * {@code RestTemplate.getRequestFactory()} returns an intercepting facade that hides the
   * closeable factory — temporarily clearing the interceptor list makes it return the raw one.
   */
  private static ClientHttpRequestFactory rawRequestFactory(RestTemplate restTemplate) {
    var interceptors = List.copyOf(restTemplate.getInterceptors());
    restTemplate.getInterceptors().clear();
    var requestFactory = restTemplate.getRequestFactory();
    restTemplate.getInterceptors().addAll(interceptors);
    return requestFactory;
  }

  private Entry buildReactiveEntry(String name, ClientProperties properties) {
    var connectionProvider = WebClientConfigUtil.buildConnectionProvider(name, properties);
    var webClient = reactiveRegistrar.createWebClient(name, name, properties, connectionProvider);
    ConnectionProvider tokenConnectionProvider = null;
    WebClient tokenWebClient = null;
    if (properties.getAuthType() == AuthType.BEARER && !properties.getBearerAuth().isUseMock()) {
      tokenConnectionProvider =
          WebClientConfigUtil.buildConnectionProvider(name + "Token", properties);
      tokenWebClient = reactiveRegistrar.createWebClient(
          name + "Token", name, properties, tokenConnectionProvider);
    }
    var tokenService = reactiveRegistrar.createTokenService(name, properties, tokenWebClient);
    var managed = new ManagedReactiveClient(webClient, tokenService);
    var tokenProviderToClose = tokenConnectionProvider;
    Runnable closer = () -> {
      connectionProvider.disposeLater().subscribe();
      if (tokenProviderToClose != null) {
        tokenProviderToClose.disposeLater().subscribe();
      }
    };
    return new Entry(ClientType.NETTY, managed, closer);
  }

  private ManagedSyncClient syncClient(String name, Entry entry) {
    if (entry.managed() instanceof ManagedSyncClient managed) {
      return managed;
    }
    throw new IllegalStateException("Client '" + name + "' is registered as " + entry.type()
        + " — use the reactive registry methods for it.");
  }

  private ManagedReactiveClient reactiveClient(String name, Entry entry) {
    if (entry.managed() instanceof ManagedReactiveClient managed) {
      return managed;
    }
    throw new IllegalStateException("Client '" + name + "' is registered as " + entry.type()
        + " — use the synchronous registry methods for it.");
  }

  private void requireSync(String name, ClientType type) {
    if (type.isReactive()) {
      throw new IllegalArgumentException("Client '" + name + "': use getOrCreateReactive/"
          + "replaceReactive for client-type " + type + ".");
    }
    if (restRegistrar == null) {
      throw new IllegalStateException("Client '" + name + "' requires the synchronous client "
          + "support. Add opentmf-http-clients-starter-rest to your classpath.");
    }
  }

  private void requireReactive(String name) {
    if (reactiveRegistrar == null) {
      throw new IllegalStateException("Client '" + name + "' requires the reactive client "
          + "support. Add opentmf-http-clients-starter-reactive to your classpath.");
    }
  }

  private void resetResilience(String name) {
    var registries = resilienceRegistriesProvider.getIfAvailable();
    if (registries != null) {
      registries.remove(name);
    }
  }

  private void scheduleClose(String name, @Nullable Entry entry) {
    if (entry == null) {
      return;
    }
    if (closeGracePeriod.isZero() || closeGracePeriod.isNegative()) {
      runCloser(name, entry.closer());
      return;
    }
    var retiringClient = new RetiringClient(name, entry.closer());
    retiring.add(retiringClient);
    closeScheduler.schedule(() -> closeRetiring(retiringClient),
        closeGracePeriod.toMillis(), TimeUnit.MILLISECONDS);
  }

  private void closeRetiring(RetiringClient retiringClient) {
    if (retiring.remove(retiringClient)) {
      runCloser(retiringClient.name(), retiringClient.closer());
    }
  }

  private void runCloser(String name, Runnable closer) {
    try {
      closer.run();
      log.debug("Closed retired client '{}'", name);
    } catch (Exception e) {
      log.warn("Error closing retired client '{}'", name, e);
    }
  }

  private void closeRequestFactory(String name, ClientHttpRequestFactory requestFactory) {
    if (requestFactory instanceof DisposableBean disposable) {
      try {
        disposable.destroy();
      } catch (Exception e) {
        log.warn("Error closing HTTP client of '{}'", name, e);
      }
    }
  }
}
