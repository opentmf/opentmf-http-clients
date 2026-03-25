package org.opentmf.client.starter.rest;

import java.io.ByteArrayInputStream;
import java.security.KeyStore;
import java.util.Base64;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
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
import org.opentmf.client.rest.service.api.RestTemplateFactory;
import org.opentmf.client.rest.util.OpenTmfResponseErrorHandler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.DefaultUriBuilderFactory;
import org.zalando.logbook.Logbook;
import org.zalando.logbook.spring.LogbookClientHttpRequestInterceptor;

@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "org.apache.hc.client5.http.impl.classic.CloseableHttpClient")
@Slf4j
public class ApacheRestTemplateFactory implements RestTemplateFactory {

  private final ObjectProvider<Logbook> logbookProvider;

  public ApacheRestTemplateFactory(ObjectProvider<Logbook> logbookProvider) {
    this.logbookProvider = logbookProvider;
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

  private SSLContext buildSslContext(ClientProperties properties) throws Exception {
    if (properties.getCertificates() == null) {
      return SSLContext.getDefault();
    }
    var certs = properties.getCertificates();

    var keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
    var keyCert = Base64.getDecoder().decode(certs.getKeyStore().getBase64Jks());
    var ksPassword = certs.getKeyStore().getPassword();
    keyStore.load(new ByteArrayInputStream(keyCert),
        ksPassword == null ? null : ksPassword.toCharArray());

    var kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    kmf.init(keyStore, certs.getKeyStore().getPkPassword().toCharArray());

    TrustManagerFactory tmf = null;
    if (certs.getTrustStore() != null) {
      var trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
      var trustCert = Base64.getDecoder().decode(certs.getTrustStore().getBase64Jks());
      var tsPassword = certs.getTrustStore().getPassword();
      trustStore.load(new ByteArrayInputStream(trustCert),
          tsPassword == null ? null : tsPassword.toCharArray());
      tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      tmf.init(trustStore);
    }

    var sslContext = SSLContext.getInstance(properties.getSslProtocol());
    sslContext.init(kmf.getKeyManagers(), tmf != null ? tmf.getTrustManagers() : null, null);
    return sslContext;
  }

  private void addFixedHeadersInterceptor(RestTemplate restTemplate, ClientProperties properties) {
    if (!CollectionUtils.isEmpty(properties.getFixedHeaders())) {
      restTemplate.getInterceptors().add((request, body, execution) -> {
        properties.getFixedHeaders().forEach((k, v) -> request.getHeaders().set(k, v));
        return execution.execute(request, body);
      });
    }
  }

  private void addLogbookInterceptor(RestTemplate restTemplate, ClientProperties properties) {
    if (properties.isLoggingEnabled()) {
      var logbook = logbookProvider.getIfAvailable();
      if (logbook != null) {
        restTemplate.getInterceptors().add(new LogbookClientHttpRequestInterceptor(logbook));
      } else {
        log.warn("Client has logging-enabled: true, but no Logbook bean found. "
            + "Add org.zalando:logbook-spring to your classpath to enable HTTP logging.");
      }
    }
  }
}
