package org.opentmf.client.test;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.opentmf.client.common.model.ClientProperties;
import org.opentmf.client.rest.service.api.SyncTokenService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

@SpringBootTest
class RestClientIT {

  @Autowired private ClientProperties syncBasicClientProperties;
  @Autowired private RestTemplate syncBasicRestTemplate;
  @Autowired private RestClient syncBasicRestClient;
  @Autowired private SyncTokenService syncBasicTokenService;

  @Autowired private ClientProperties syncBearerClientProperties;
  @Autowired private RestTemplate syncBearerRestTemplate;
  @Autowired private RestClient syncBearerRestClient;
  @Autowired private SyncTokenService syncBearerTokenService;

  @Test
  void testSyncBasicClient_exposeCorrectBeans() {
    Assertions.assertNotNull(syncBasicClientProperties);
    Assertions.assertNotNull(syncBasicRestTemplate);
    Assertions.assertNotNull(syncBasicRestClient);
    Assertions.assertNotNull(syncBasicTokenService);

    String token = syncBasicTokenService.getToken();
    Assertions.assertNotNull(token);
    Assertions.assertFalse(token.isEmpty());
  }

  @Test
  void testSyncBearerMockClient_exposeCorrectBeans() {
    Assertions.assertNotNull(syncBearerClientProperties);
    Assertions.assertNotNull(syncBearerRestTemplate);
    Assertions.assertNotNull(syncBearerRestClient);
    Assertions.assertNotNull(syncBearerTokenService);

    String token = syncBearerTokenService.getToken();
    Assertions.assertNotNull(token);
    Assertions.assertFalse(token.isEmpty());
  }
}
