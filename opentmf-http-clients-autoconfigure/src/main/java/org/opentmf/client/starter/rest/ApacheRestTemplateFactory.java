package org.opentmf.client.starter.rest;

import java.io.ByteArrayInputStream;
import java.security.KeyStore;
import java.util.Base64;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.impl.routing.DefaultProxyRoutePlanner;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.client5.http.ssl.SSLConnectionSocketFactoryBuilder;
import org.apache.hc.core5.http.io.SocketConfig;
import org.apache.hc.core5.util.Timeout;
import org.opentmf.client.common.model.ClientProperties;
import org.opentmf.client.rest.service.api.RestTemplateFactory;
import org.opentmf.client.rest.util.OpenTmfResponseErrorHandler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = "org.apache.hc.client5.http.impl.classic.CloseableHttpClient")
@Slf4j
public class ApacheRestTemplateFactory implements RestTemplateFactory {

  @Override
  public RestTemplate create(String clientId, ClientProperties properties) {
    log.debug("Creating Apache HttpClient 5 RestTemplate for client: {}", clientId);
    try {
      var sslContext = buildSslContext(properties);

      var sslSocketFactory = SSLConnectionSocketFactoryBuilder.create()
          .setSslContext(sslContext)
          .build();

      var socketConfig = SocketConfig.custom()
          .setSoTimeout(Timeout.ofMilliseconds(properties.getResponseTimeoutMillis()))
          .build();

      var connManager = PoolingHttpClientConnectionManagerBuilder.create()
          .setSSLSocketFactory(sslSocketFactory)
          .setMaxConnTotal(properties.getMaxConnections())
          .setMaxConnPerRoute(properties.getMaxConnections())
          .setDefaultSocketConfig(socketConfig)
          .build();

      var requestConfig = RequestConfig.custom()
          .setConnectionRequestTimeout(Timeout.ofMilliseconds(properties.getRequestTimeoutMillis()))
          .setResponseTimeout(Timeout.ofMilliseconds(properties.getResponseTimeoutMillis()))
          .build();

      HttpClientBuilder httpClientBuilder = HttpClients.custom()
          .setConnectionManager(connManager)
          .setDefaultRequestConfig(requestConfig);

      if (properties.getProxyConfig() != null) {
        var proxyConfig = properties.getProxyConfig();
        var proxy = new HttpHost(proxyConfig.getProxyHost(), proxyConfig.getProxyPort());
        httpClientBuilder.setRoutePlanner(new DefaultProxyRoutePlanner(proxy));
      }

      var httpClient = httpClientBuilder.build();

      var restTemplate = new RestTemplate(new HttpComponentsClientHttpRequestFactory(httpClient));
      restTemplate.setErrorHandler(new OpenTmfResponseErrorHandler());
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

    var sslContext = SSLContext.getInstance("TLS");
    sslContext.init(kmf.getKeyManagers(), tmf != null ? tmf.getTrustManagers() : null, null);
    return sslContext;
  }
}
