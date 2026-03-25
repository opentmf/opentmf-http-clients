package org.opentmf.client.reactive.util;

import io.netty.channel.ChannelOption;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.security.KeyStore;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLException;
import javax.net.ssl.TrustManagerFactory;
import lombok.Generated;
import org.jspecify.annotations.Nullable;
import org.opentmf.client.common.exception.OpenTmfClientNotFoundException;
import org.opentmf.client.common.exception.OpenTmfClientResponseException;
import org.opentmf.client.common.model.ClientProperties;
import org.opentmf.client.common.util.ErrorBodyExtractor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.netty.Connection;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.netty.transport.ProxyProvider;
import reactor.netty.transport.logging.AdvancedByteBufFormat;

public final class WebClientConfigUtil {

  @Generated
  private WebClientConfigUtil() {
  }

  private static final int MAX_IN_MEMORY = 16 * 1024 * 1024;

  public static HttpClient httpClient(
      @Nullable Consumer<Connection> logbookHandler,
      String clientId,
      ClientProperties clientProperties) throws SSLException {
    var sslContext = buildSslContext(clientProperties);
    var httpClient = httpClient(logbookHandler, sslContext, clientId, clientProperties);

    if (clientProperties.getProxyConfig() != null) {
      httpClient = httpClient.proxy(typeSpec -> proxy(typeSpec, clientProperties));
    }
    return httpClient;
  }

  private static HttpClient httpClient(
      @Nullable Consumer<Connection> logbookHandler,
      SslContext sslContext,
      String clientId,
      ClientProperties clientProperties) {
    var connectionProvider = buildConnectionProvider(clientId, clientProperties);
    var client = HttpClient.create(connectionProvider)
        .wiretap(HttpClient.class.getName(), LogLevel.INFO, AdvancedByteBufFormat.SIMPLE)
        .compress(clientProperties.isCompressionEnabled())
        .responseTimeout(clientProperties.getResponseTimeout())
        .keepAlive(true)
        .secure(spec ->
            spec.sslContext(sslContext).handshakeTimeout(clientProperties.getResponseTimeout()))
        .option(ChannelOption.CONNECT_TIMEOUT_MILLIS,
            (int) clientProperties.getRequestTimeout().toMillis())
        .option(ChannelOption.SO_KEEPALIVE, true)
        .doOnConnected(connection -> doOnConnected(connection, logbookHandler, clientProperties));

    if (clientProperties.isFollowRedirects()) {
      client = client.followRedirect(true);
    }
    return client;
  }

  public static WebClient createWebClient(WebClient.Builder webClientBuilder, HttpClient httpClient,
      ClientProperties clientProperties) {
    var builder = webClientBuilder.defaultHeaders((HttpHeaders httpHeaders) -> {
          httpHeaders.add(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);
          if (!CollectionUtils.isEmpty(clientProperties.getFixedHeaders())) {
            clientProperties.getFixedHeaders().forEach(httpHeaders::add);
          }
        })
        .clientConnector(new ReactorClientHttpConnector(httpClient))
        .filter(errorWrappingFilter())
        .exchangeStrategies(buildExchangeStrategies());

    if (StringUtils.hasText(clientProperties.getBaseUrl())) {
      builder.baseUrl(clientProperties.getBaseUrl());
    }
    return builder.build();
  }

  /**
   * An {@link ExchangeFilterFunction} that converts HTTP error responses into
   * {@link OpenTmfClientResponseException} (or {@link OpenTmfClientNotFoundException} for 404).
   * Applied automatically on all library-created {@code WebClient} instances.
   */
  public static ExchangeFilterFunction errorWrappingFilter() {
    return ExchangeFilterFunction.ofResponseProcessor(response -> {
      if (!response.statusCode().isError()) {
        return Mono.just(response);
      }
      var status = response.statusCode();
      return response.bodyToMono(byte[].class)
          .defaultIfEmpty(new byte[0])
          .flatMap(body -> {
            String rawBody = ErrorBodyExtractor.decodeAsText(body);
            String message = ErrorBodyExtractor.extractMessage(status, body);
            OpenTmfClientResponseException ex = status.isSameCodeAs(HttpStatus.NOT_FOUND)
                ? new OpenTmfClientNotFoundException(status, message, rawBody)
                : new OpenTmfClientResponseException(status, message, rawBody);
            return Mono.error(ex);
          });
    });
  }

  private static ExchangeStrategies buildExchangeStrategies() {
    return ExchangeStrategies.builder()
        .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY))
        .build();
  }

  private static SslContext buildSslContext(ClientProperties clientProperties) throws SSLException {
    if (clientProperties.getCertificates() != null) {
      return buildMtlsSslContext(clientProperties);
    }
    return buildGenericSslContext(clientProperties);
  }

  private static SslContext buildMtlsSslContext(ClientProperties clientProperties) {
    try {
      var keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
      var keyCert = Base64.getDecoder().decode(
          clientProperties.getCertificates().getKeyStore().getBase64Jks());
      var keyStorePassword = clientProperties.getCertificates().getKeyStore().getPassword();
      var keyManagerFactory = KeyManagerFactory.getInstance("SunX509");

      keyStore.load(new ByteArrayInputStream(keyCert),
          keyStorePassword == null ? null : keyStorePassword.toCharArray());
      keyManagerFactory.init(keyStore,
          clientProperties.getCertificates().getKeyStore().getPkPassword().toCharArray());

      var sslBuilder = SslContextBuilder.forClient()
          .keyManager(keyManagerFactory);
      applySslProtocol(sslBuilder, clientProperties);

      if (clientProperties.getCertificates().getTrustStore() != null) {
        var trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
        var trustCert = Base64.getDecoder().decode(
            clientProperties.getCertificates().getTrustStore().getBase64Jks());
        var trustStorePassword = clientProperties.getCertificates().getTrustStore().getPassword();
        var trustManagerFactory = TrustManagerFactory.getInstance("SunX509");
        trustStore.load(
            new java.io.ByteArrayInputStream(trustCert),
            trustStorePassword == null ? null : trustStorePassword.toCharArray());
        trustManagerFactory.init(trustStore);
        sslBuilder.trustManager(trustManagerFactory);
      }
      return sslBuilder.build();
    } catch (Exception e) {
      throw new IllegalArgumentException(
          "Error creating 2-Way TLS WebClient. Check key-store and trust-store.", e);
    }
  }

  private static SslContext buildGenericSslContext(ClientProperties clientProperties)
      throws SSLException {
    var sslBuilder = SslContextBuilder.forClient()
        .trustManager(InsecureTrustManagerFactory.INSTANCE);
    applySslProtocol(sslBuilder, clientProperties);
    return sslBuilder.build();
  }

  private static void applySslProtocol(SslContextBuilder sslBuilder,
      ClientProperties clientProperties) {
    var protocol = clientProperties.getSslProtocol();
    if (!"TLS".equalsIgnoreCase(protocol)) {
      sslBuilder.protocols(protocol);
    }
  }

  private static ConnectionProvider buildConnectionProvider(
      String clientId, ClientProperties clientProperties) {
    var builder = ConnectionProvider.builder(clientId)
        .maxIdleTime(clientProperties.getConnectionIdleTimeout())
        .maxConnections(clientProperties.getMaxConnections());
    if (isMicrometerAvailable()) {
      builder.metrics(true);
    }
    return builder.build();
  }

  private static boolean isMicrometerAvailable() {
    try {
      Class.forName("io.micrometer.core.instrument.Metrics");
      return true;
    } catch (ClassNotFoundException e) {
      return false;
    }
  }

  private static void doOnConnected(Connection conn,
      @Nullable Consumer<Connection> logbookHandler,
      ClientProperties clientProperties) {
    conn.addHandlerLast(new ReadTimeoutHandler(
            clientProperties.getResponseTimeout().toMillis(), TimeUnit.MILLISECONDS))
        .addHandlerLast(new WriteTimeoutHandler(
            clientProperties.getRequestTimeout().toMillis(), TimeUnit.MILLISECONDS));
    if (clientProperties.isLoggingEnabled() && logbookHandler != null) {
      logbookHandler.accept(conn);
    }
  }

  public static void proxy(ProxyProvider.TypeSpec typeSpec,
      ClientProperties clientProperties) {
    var proxyConfig = Objects.requireNonNull(clientProperties.getProxyConfig(), "ProxyConfig cannot be null");
    typeSpec.type(ProxyProvider.Proxy.HTTP)
        .socketAddress(InetSocketAddress.createUnresolved(proxyConfig.getProxyHost(), proxyConfig.getProxyPort()))
        .connectTimeoutMillis(clientProperties.getResponseTimeout().toMillis())
        .nonProxyHosts(nonProxyHostsPattern(clientProperties));
  }

  private static String nonProxyHostsPattern(ClientProperties clientProperties) {
    var proxyConfig = Objects.requireNonNull(clientProperties.getProxyConfig(), "ProxyConfig cannot be null");
    if (CollectionUtils.isEmpty(proxyConfig.getNonProxyHosts())) {
      return "";
    }
    return StringUtils.collectionToDelimitedString(proxyConfig.getNonProxyHosts(), "|");
  }
}
