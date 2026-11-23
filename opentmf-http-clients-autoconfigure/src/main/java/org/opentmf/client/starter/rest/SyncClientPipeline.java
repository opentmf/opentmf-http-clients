package org.opentmf.client.starter.rest;

import io.micrometer.observation.ObservationRegistry;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.opentmf.client.common.model.ClientProperties;
import org.opentmf.client.rest.util.OpenTmfResponseErrorHandler;
import org.opentmf.client.rest.util.OpenTmfRestClientStatusHandler;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.converter.HttpMessageConverters;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.DefaultUriBuilderFactory;

/**
 * Everything the library builds for one synchronous client, once: the backend's request factory
 * (its HTTP client with mTLS, pool and time limits), the ordered interceptors, the application's
 * {@link ObservationRegistry}, the base URL and the error handling of the client's settings. Its
 * {@link #restClient()} is built from these directly; {@link #restTemplate()} applies the same
 * parts to a {@code RestTemplate}. Both share the one request factory, so the one connection
 * pool.
 *
 * <p>Built by the library's JDK and Apache factories; an application does not build one.</p>
 *
 * @since 2.6.0
 */
public final class SyncClientPipeline {

  private final ClientHttpRequestFactory requestFactory;
  private final List<ClientHttpRequestInterceptor> interceptors;
  private final @Nullable ObservationRegistry observationRegistry;
  private final ClientProperties properties;
  private final List<HttpMessageConverter<?>> messageConverters;
  private final RestClient restClient;

  /**
   * @throws IllegalArgumentException when the client's {@code peer-text-cap-length} is outside
   *     its bounds
   */
  SyncClientPipeline(ClientHttpRequestFactory requestFactory,
      List<ClientHttpRequestInterceptor> interceptors,
      @Nullable ObservationRegistry observationRegistry, ClientProperties properties) {
    this.requestFactory = requestFactory;
    this.interceptors = List.copyOf(interceptors);
    this.observationRegistry = observationRegistry;
    this.properties = properties;
    this.messageConverters = defaultMessageConverters();
    this.restClient = buildRestClient(
        OpenTmfRestClientStatusHandler.errorHandler(properties));
  }

  /**
   * The backend's own request factory, which owns the client's HTTP client and connection pool.
   */
  public ClientHttpRequestFactory requestFactory() {
    return requestFactory;
  }

  /**
   * The client's interceptors in their order: the application's contributions first, then the
   * library's resilience, backend-specific, fixed-headers and Logbook interceptors.
   */
  public List<ClientHttpRequestInterceptor> interceptors() {
    return interceptors;
  }

  /** The client's settings the pipeline was built from. */
  ClientProperties properties() {
    return properties;
  }

  /** The client's {@code RestClient}; the same instance on every call. */
  public RestClient restClient() {
    return restClient;
  }

  /**
   * A new {@code RestTemplate} over the same parts as {@link #restClient()}: the same request
   * factory, interceptors and message converters, the same observation registry and base URL, and
   * {@link OpenTmfResponseErrorHandler}, which throws the same exceptions.
   *
   * <p>Since 2.6.0: Spring marks {@code RestTemplate} for removal; use {@link #restClient()}. This
   * one stays supported.</p>
   */
  public RestTemplate restTemplate() {
    var restTemplate = new RestTemplate(messageConverters);
    restTemplate.setRequestFactory(requestFactory);
    restTemplate.setErrorHandler(new OpenTmfResponseErrorHandler(properties));
    if (observationRegistry != null) {
      restTemplate.setObservationRegistry(observationRegistry);
    }
    restTemplate.getInterceptors().addAll(interceptors);
    if (StringUtils.hasText(properties.getBaseUrl())) {
      restTemplate.setUriTemplateHandler(new DefaultUriBuilderFactory(properties.getBaseUrl()));
    }
    return restTemplate;
  }

  /**
   * The {@code RestClient} carries the pipeline's converters, not Spring's defaults: a client
   * derived from it ({@code mutate()...build()}, as a consumer does per call to add a credential)
   * then reuses them. Without them every such build assembles Spring's default set anew, which
   * detects the classpath (the JAXB converter scans jars), about 15 ms each. The converters are the
   * {@code RestTemplate}'s, the same instances in the same order, and Spring appends one form
   * converter: the layout of a client built with {@code RestClient.builder(restTemplate)}.
   */
  private RestClient buildRestClient(RestClient.ResponseSpec.ErrorHandler errorHandler) {
    var converters = messageConverters;
    var builder = RestClient.builder()
        .requestFactory(requestFactory)
        .configureMessageConverters(
            converterBuilder -> converters.forEach(converterBuilder::addCustomConverter))
        .requestInterceptors(chain -> chain.addAll(interceptors))
        .defaultStatusHandler(HttpStatusCode::isError, errorHandler);
    if (observationRegistry != null) {
      builder.observationRegistry(observationRegistry);
    }
    if (StringUtils.hasText(properties.getBaseUrl())) {
      builder.uriBuilderFactory(new DefaultUriBuilderFactory(properties.getBaseUrl()));
    }
    return builder.build();
  }

  /** Spring's default client converters, as {@code new RestTemplate()} has them, assembled once. */
  private static List<HttpMessageConverter<?>> defaultMessageConverters() {
    var converters = new ArrayList<HttpMessageConverter<?>>();
    HttpMessageConverters.forClient().registerDefaults().build().forEach(converters::add);
    return List.copyOf(converters);
  }
}
