package org.opentmf.client.starter.rest;

import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.impl.routing.DefaultProxyRoutePlanner;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.io.SocketConfig;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.opentmf.client.common.model.ClientProperties;
import org.opentmf.client.common.resilience.ResilienceRegistries;
import org.opentmf.client.rest.util.OpenTmfResponseErrorHandler;
import org.opentmf.client.starter.ApachePoolMeters;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.DefaultUriBuilderFactory;

@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "org.apache.hc.client5.http.impl.classic.CloseableHttpClient")
@Slf4j
public class ApacheRestTemplateFactory extends AbstractRestTemplateFactory {

  private final ApachePoolMeters poolMeters;

  public ApacheRestTemplateFactory(ObjectProvider<RestLogbookSupport> logbookSupportProvider,
      ObjectProvider<ResilienceRegistries> resilienceRegistriesProvider,
      ObjectProvider<ApachePoolMeters> poolMetersProvider) {
    super(logbookSupportProvider, resilienceRegistriesProvider);
    this.poolMeters = poolMetersProvider.getIfAvailable();
  }

  @Override
  public RestTemplate create(String clientId, ClientProperties properties) {
    log.debug("Creating Apache HttpClient 5 RestTemplate for client: {}", clientId);
    try {
      var sslContext = buildSslContext(properties);

      var tlsStrategy = ClientTlsStrategyBuilder.create()
          .setSslContext(sslContext)
          .buildClassic();

      var socketConfig = SocketConfig.custom()
          .setSoTimeout(Timeout.ofMilliseconds(properties.getResponseTimeout().toMillis()))
          .build();

      var connectionConfig = ConnectionConfig.custom()
          .setTimeToLive(TimeValue.ofMilliseconds(
              properties.getConnectionIdleTimeout().toMillis()))
          .build();

      var connManager = PoolingHttpClientConnectionManagerBuilder.create()
          .setTlsSocketStrategy(tlsStrategy)
          .setMaxConnTotal(properties.getMaxConnections())
          .setMaxConnPerRoute(properties.getEffectiveMaxConnectionsPerRoute())
          .setDefaultSocketConfig(socketConfig)
          .setDefaultConnectionConfig(connectionConfig)
          .build();

      if (poolMeters != null) {
        poolMeters.register(clientId, connManager);
      }

      var requestConfig = RequestConfig.custom()
          .setConnectionRequestTimeout(Timeout.ofMilliseconds(
              properties.getRequestTimeout().toMillis()))
          .setResponseTimeout(Timeout.ofMilliseconds(
              properties.getResponseTimeout().toMillis()))
          .setRedirectsEnabled(properties.isFollowRedirects())
          .build();

      HttpClientBuilder httpClientBuilder = HttpClients.custom()
          .setConnectionManager(connManager)
          .setDefaultRequestConfig(requestConfig)
          .evictIdleConnections(TimeValue.ofMilliseconds(
              properties.getConnectionIdleTimeout().toMillis()));

      if (!properties.isCompressionEnabled()) {
        httpClientBuilder.disableContentCompression();
      }

      if (properties.getProxyConfig() != null) {
        var proxyConfig = properties.getProxyConfig();
        var proxy = new HttpHost(proxyConfig.getProxyHost(), proxyConfig.getProxyPort());
        httpClientBuilder.setRoutePlanner(new DefaultProxyRoutePlanner(proxy));
      }

      var httpClient = httpClientBuilder.build();

      var restTemplate = new RestTemplate(new HttpComponentsClientHttpRequestFactory(httpClient));
      restTemplate.setErrorHandler(new OpenTmfResponseErrorHandler());
      addResilienceInterceptor(restTemplate, clientId, properties);
      addFixedHeadersInterceptor(restTemplate, properties);
      addLogbookInterceptor(restTemplate, properties);

      if (StringUtils.hasText(properties.getBaseUrl())) {
        restTemplate.setUriTemplateHandler(
            new DefaultUriBuilderFactory(properties.getBaseUrl()));
      }
      return restTemplate;
    } catch (Exception e) {
      throw new IllegalArgumentException("Failed to create Apache RestTemplate for " + clientId, e);
    }
  }
}
