package org.opentmf.client.starter.rest;

import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.security.KeyStore;
import java.util.Base64;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import lombok.extern.slf4j.Slf4j;
import org.opentmf.client.common.model.ClientProperties;
import org.opentmf.client.rest.service.api.RestTemplateFactory;
import org.opentmf.client.rest.util.GzipClientHttpRequestInterceptor;
import org.opentmf.client.rest.util.OpenTmfResponseErrorHandler;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.DefaultUriBuilderFactory;

@Configuration(proxyBeanMethods = false)
@Slf4j
public class JdkRestTemplateFactory implements RestTemplateFactory {

  private final RestLogbookSupport logbookSupport;

  public JdkRestTemplateFactory(ObjectProvider<RestLogbookSupport> logbookSupportProvider) {
    this.logbookSupport = logbookSupportProvider.getIfAvailable();
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

  private SSLContext buildSslContext(ClientProperties properties) throws Exception {
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
        properties.getFixedHeaders().forEach((k, v) -> {
          if (!request.getHeaders().containsHeader(k)) {
            request.getHeaders().set(k, v);
          }
        });
        return execution.execute(request, body);
      });
    }
  }

  private void addLogbookInterceptor(RestTemplate restTemplate, ClientProperties properties) {
    if (properties.isLoggingEnabled()) {
      if (logbookSupport != null) {
        logbookSupport.addInterceptor(restTemplate);
      } else {
        log.warn("Client has logging-enabled: true, but no Logbook bean found. "
            + "Add org.zalando:logbook-spring to your classpath to enable HTTP logging.");
      }
    }
  }
}
