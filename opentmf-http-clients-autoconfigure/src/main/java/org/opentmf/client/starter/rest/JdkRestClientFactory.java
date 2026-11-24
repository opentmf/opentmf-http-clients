package org.opentmf.client.starter.rest;

import io.micrometer.observation.ObservationRegistry;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import lombok.extern.slf4j.Slf4j;
import org.opentmf.client.common.model.ClientProperties;
import org.opentmf.client.common.resilience.ResilienceRegistries;
import org.opentmf.client.rest.service.api.OutboundInterceptorContribution;
import org.opentmf.client.rest.util.GzipClientHttpRequestInterceptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestInterceptor;

@Configuration(proxyBeanMethods = false)
@Slf4j
public class JdkRestClientFactory extends AbstractRestClientFactory {

  public JdkRestClientFactory(ObjectProvider<RestLogbookSupport> logbookSupportProvider,
      ObjectProvider<ResilienceRegistries> resilienceRegistriesProvider,
      ObjectProvider<ObservationRegistry> observationRegistryProvider,
      ObjectProvider<OutboundInterceptorContribution> contributionsProvider) {
    super(logbookSupportProvider, resilienceRegistriesProvider, observationRegistryProvider,
        contributionsProvider);
  }

  @Override
  SyncClientPipeline pipeline(String clientId, ClientProperties properties) {
    log.debug("Creating JDK HttpClient RestClient for client: {}", clientId);
    try {
      var builder = HttpClient.newBuilder()
          .connectTimeout(properties.getRequestTimeout())
          .followRedirects(properties.isFollowRedirects()
              ? HttpClient.Redirect.NORMAL : HttpClient.Redirect.NEVER);

      if (properties.getCertificates() != null) {
        builder.sslContext(buildSslContext(properties));
      }

      if (properties.getProxyConfig() != null) {
        var proxyConfig = properties.getProxyConfig();
        builder.proxy(ProxySelector.of(
            new InetSocketAddress(proxyConfig.getProxyHost(), proxyConfig.getProxyPort())));
      }

      var requestFactory = new CloseableJdkClientHttpRequestFactory(builder.build());
      requestFactory.setReadTimeout(readTimeout(properties));
      var interceptors = new ArrayList<ClientHttpRequestInterceptor>();
      addContributedInterceptors(interceptors);
      addResilienceInterceptor(interceptors, clientId, properties);
      if (properties.isCompressionEnabled()) {
        interceptors.add(GzipClientHttpRequestInterceptor.instance());
      }
      addFixedHeadersInterceptor(interceptors, properties);
      addLogbookInterceptor(interceptors, properties);
      return pipelineOf(requestFactory, interceptors, properties);
    } catch (Exception e) {
      throw new IllegalArgumentException("Failed to create JDK RestClient for " + clientId, e);
    }
  }

  /**
   * The JDK request factory's read timeout, which is one deadline for the whole exchange:
   * {@code response-timeout}, or {@code exchange-timeout} when that is set and smaller.
   */
  static Duration readTimeout(ClientProperties properties) {
    var exchangeTimeout = properties.getExchangeTimeout();
    var responseTimeout = properties.getResponseTimeout();
    return exchangeTimeout != null && exchangeTimeout.compareTo(responseTimeout) < 0
        ? exchangeTimeout
        : responseTimeout;
  }
}
