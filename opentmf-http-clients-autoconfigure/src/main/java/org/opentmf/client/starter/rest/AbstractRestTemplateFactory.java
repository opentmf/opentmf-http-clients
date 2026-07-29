package org.opentmf.client.starter.rest;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Base64;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import lombok.extern.slf4j.Slf4j;
import org.opentmf.client.common.model.ClientProperties;
import org.opentmf.client.rest.service.api.RestTemplateFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.CollectionUtils;
import org.springframework.web.client.RestTemplate;

/**
 * Shared plumbing for the {@link RestTemplateFactory} implementations: mTLS
 * {@link SSLContext} construction from base64-encoded JKS material, the fixed-headers
 * interceptor (defaults only — per-request headers win), and optional Logbook wiring.
 */
@Slf4j
abstract class AbstractRestTemplateFactory implements RestTemplateFactory {

  private final RestLogbookSupport logbookSupport;

  protected AbstractRestTemplateFactory(ObjectProvider<RestLogbookSupport> logbookSupportProvider) {
    this.logbookSupport = logbookSupportProvider.getIfAvailable();
  }

  protected SSLContext buildSslContext(ClientProperties properties)
      throws GeneralSecurityException, IOException {
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

  protected void addFixedHeadersInterceptor(RestTemplate restTemplate,
      ClientProperties properties) {
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

  protected void addLogbookInterceptor(RestTemplate restTemplate, ClientProperties properties) {
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
