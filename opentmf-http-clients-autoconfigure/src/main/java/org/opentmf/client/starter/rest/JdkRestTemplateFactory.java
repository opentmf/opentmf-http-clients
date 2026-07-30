package org.opentmf.client.starter.rest;

import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import lombok.extern.slf4j.Slf4j;
import org.opentmf.client.common.model.ClientProperties;
import org.opentmf.client.common.resilience.ResilienceRegistries;
import org.opentmf.client.rest.util.GzipClientHttpRequestInterceptor;
import org.opentmf.client.rest.util.OpenTmfResponseErrorHandler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.DefaultUriBuilderFactory;

@Configuration(proxyBeanMethods = false)
@Slf4j
public class JdkRestTemplateFactory extends AbstractRestTemplateFactory {

  public JdkRestTemplateFactory(ObjectProvider<RestLogbookSupport> logbookSupportProvider,
      ObjectProvider<ResilienceRegistries> resilienceRegistriesProvider) {
    super(logbookSupportProvider, resilienceRegistriesProvider);
  }

  @Override
  public RestTemplate create(String clientId, ClientProperties properties) {
    log.debug("Creating JDK HttpClient RestTemplate for client: {}", clientId);
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

      var requestFactory = new JdkClientHttpRequestFactory(builder.build());
      requestFactory.setReadTimeout(properties.getResponseTimeout());
      var restTemplate = new RestTemplate(requestFactory);
      restTemplate.setErrorHandler(new OpenTmfResponseErrorHandler());
      addResilienceInterceptor(restTemplate, clientId, properties);

      if (properties.isCompressionEnabled()) {
        restTemplate.getInterceptors().add(GzipClientHttpRequestInterceptor.instance());
      }
      addFixedHeadersInterceptor(restTemplate, properties);
      addLogbookInterceptor(restTemplate, properties);

      if (StringUtils.hasText(properties.getBaseUrl())) {
        restTemplate.setUriTemplateHandler(
            new DefaultUriBuilderFactory(properties.getBaseUrl()));
      }
      return restTemplate;
    } catch (Exception e) {
      throw new IllegalArgumentException("Failed to create JDK RestTemplate for " + clientId, e);
    }
  }
}
