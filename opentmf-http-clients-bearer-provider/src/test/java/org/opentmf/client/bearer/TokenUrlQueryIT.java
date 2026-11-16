package org.opentmf.client.bearer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockserver.integration.ClientAndServer;
import org.opentmf.client.bearer.exception.BearerTokenException;
import org.opentmf.client.bearer.model.TokenEntry;
import org.opentmf.client.bearer.observe.RecordingTokenFetchListener;
import org.opentmf.client.bearer.observe.RecordingTokenFetchListener.Notification;
import org.opentmf.client.bearer.observe.TokenFetchListener.Outcome;
import org.opentmf.client.bearer.reactive.BearerTokenClientImpl;
import org.opentmf.client.bearer.reactive.BearerTokenServiceImpl;
import org.opentmf.client.bearer.sync.SyncBearerTokenServiceImpl;
import org.opentmf.client.bearer.sync.SyncTokenClientImpl;
import org.opentmf.client.common.model.BearerAuthConfig;
import org.opentmf.client.common.model.ClientProperties;
import org.opentmf.client.rest.util.OpenTmfRestClientStatusHandler;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * A token URL with a query is called with its query, and a {@code TokenFetchListener} receives
 * it without the query, on a mint and on a failure, on the sync and the reactive token client.
 * The query value below is a canary, not a credential.
 */
class TokenUrlQueryIT {

  private static final String TOKEN_PATH = "/token";
  private static final String CANARY = "canaryquery9b2e";

  private static ClientAndServer mockServer;
  private static URI plainUrl;
  private static URI queryUrl;

  private RecordingTokenFetchListener listener;

  @BeforeAll
  static void startMockServer() {
    mockServer = ClientAndServer.startClientAndServer();
    plainUrl = URI.create("http://localhost:" + mockServer.getLocalPort() + TOKEN_PATH);
    queryUrl = URI.create(plainUrl + "?client_secret=" + CANARY);
  }

  @AfterAll
  static void stopMockServer() {
    mockServer.stop();
  }

  @BeforeEach
  void setUp() {
    mockServer.reset();
    listener = new RecordingTokenFetchListener();
  }

  private String mint(String flavour) {
    var config = new BearerAuthConfig();
    config.setTokenUrl(queryUrl);
    config.setFormData(Map.of("grant_type", "client_credentials"));
    var properties = new ClientProperties();
    properties.setNumRetries(0);
    var cache = Caffeine.newBuilder().<String, TokenEntry>build();
    if ("sync".equals(flavour)) {
      var restClient = RestClient.builder()
          .defaultStatusHandler(HttpStatusCode::isError,
              OpenTmfRestClientStatusHandler.errorHandler())
          .build();
      return new SyncBearerTokenServiceImpl(config, cache,
          new SyncTokenClientImpl(restClient, properties, config, listener)).getToken();
    }
    return new BearerTokenServiceImpl(config, cache,
        new BearerTokenClientImpl(properties, config, WebClient.create(), listener))
        .getToken().block(Duration.ofSeconds(10));
  }

  @ParameterizedTest(name = "{0} {1}")
  @CsvSource({"sync, 200", "sync, 401", "reactive, 200", "reactive, 401"})
  void theListener_getsTheUrlWithoutTheQuery_theCallCarriesIt(String flavour, int status) {
    mockServer.when(request().withMethod("POST").withPath(TOKEN_PATH))
        .respond(response().withStatusCode(status)
            .withHeader("Content-Type", "application/json")
            .withBody(status == 200 ? "{\"access_token\":\"t\",\"expires_in\":3600}"
                : "{\"error\":\"invalid_client\"}"));

    var failure = catchThrowable(() -> mint(flavour));

    var outcome = status == 200 ? Outcome.OK : Outcome.FAILED;
    assertThat(listener.notifications()).containsExactly(new Notification(plainUrl, outcome));
    if (status == 200) {
      assertThat(failure).isNull();
    } else {
      assertThat(failure).isInstanceOf(BearerTokenException.class);
      assertThat(((BearerTokenException) failure).getTokenUrl()).isEqualTo(plainUrl);
      assertThat(failure.getMessage()).doesNotContain(CANARY);
    }
    var received = mockServer.retrieveRecordedRequests(request().withPath(TOKEN_PATH));
    assertThat(received).hasSize(1);
    assertThat(received[0].getFirstQueryStringParameter("client_secret")).isEqualTo(CANARY);
  }
}
