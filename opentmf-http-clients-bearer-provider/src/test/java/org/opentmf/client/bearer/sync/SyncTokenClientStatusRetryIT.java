package org.opentmf.client.bearer.sync;

import static org.assertj.core.api.Assertions.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.matchers.Times;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.MediaType;
import org.opentmf.client.bearer.observe.RecordingTokenFetchListener;
import org.opentmf.client.bearer.observe.TokenFetchListener.Outcome;
import org.opentmf.client.common.exception.OpenTmfClientResponseException;
import org.opentmf.client.common.model.BearerAuthConfig;
import org.opentmf.client.common.model.ClientProperties;
import org.opentmf.client.rest.util.OpenTmfRestClientStatusHandler;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

/**
 * Status-code retry parity with the reactive token client: a retryable status from the token
 * endpoint is retried per the client's {@code num-retries} / {@code retry-wait-duration} /
 * {@code max-retry-after}; a non-retryable one (the identity provider's verdict) is not.
 */
class SyncTokenClientStatusRetryIT {

  private static final String TOKEN_PATH = "/realms/realm1/protocol/openid-connect/token";
  private static final String TOKEN_JSON = "{\"access_token\":\"tok-1\",\"expires_in\":300}";

  private static ClientAndServer mockServer;
  private static URI tokenUrl;

  private RecordingTokenFetchListener listener;
  private RestClient restClient;
  private BearerAuthConfig config;
  private ListAppender<ILoggingEvent> logEvents;

  @BeforeAll
  static void startMockServer() {
    mockServer = ClientAndServer.startClientAndServer();
    tokenUrl = URI.create("http://localhost:" + mockServer.getLocalPort() + TOKEN_PATH);
  }

  @AfterAll
  static void stopMockServer() {
    mockServer.stop();
  }

  @BeforeEach
  void setUp() {
    mockServer.reset();
    listener = new RecordingTokenFetchListener();

    config = new BearerAuthConfig();
    config.setTokenUrl(tokenUrl);
    config.setClientId("client1");
    config.setClientSecret("client1Secret");
    config.setFormData(Map.of("grant_type", "client_credentials"));

    restClient = RestClient.builder()
        .defaultStatusHandler(HttpStatusCode::isError,
            OpenTmfRestClientStatusHandler.errorHandler())
        .build();

    logEvents = new ListAppender<>();
    logEvents.start();
    ((Logger) LoggerFactory.getLogger(SyncTokenClientImpl.class)).addAppender(logEvents);
  }

  @AfterEach
  void tearDown() {
    ((Logger) LoggerFactory.getLogger(SyncTokenClientImpl.class)).detachAppender(logEvents);
  }

  @Test
  void retryableStatus_isRetriedPerNumRetries_andSucceeds() {
    mockServer.when(tokenRequest(), Times.once()).respond(response().withStatusCode(503));
    mockServer.when(tokenRequest()).respond(tokenResponse());

    var token = tokenClient(2).getToken(tokenUrl, formData());

    assertThat(token.get("access_token").asString()).isEqualTo("tok-1");
    assertThat(mockServer.retrieveRecordedRequests(tokenRequest())).hasSize(2);
    assertThat(listener.outcomes()).containsExactly(Outcome.RETRIED);
    assertThat(infoMessages()).singleElement(as(STRING))
        .contains("minted from " + tokenUrl, "attempt 2");
  }

  @Test
  void retryableStatus_beyondNumRetries_failsWithTheStatusError() {
    mockServer.when(tokenRequest()).respond(response().withStatusCode(503));

    var client = tokenClient(1);
    var form = formData();
    assertThatThrownBy(() -> client.getToken(tokenUrl, form))
        .isInstanceOf(OpenTmfClientResponseException.class)
        .satisfies(e -> assertThat(((OpenTmfClientResponseException) e).getStatusCode().value())
            .isEqualTo(503));
    assertThat(mockServer.retrieveRecordedRequests(tokenRequest())).hasSize(2);
    assertThat(listener.outcomes()).containsExactly(Outcome.FAILED);
  }

  @Test
  void nonRetryableStatus_isNotRetried() {
    mockServer.when(tokenRequest()).respond(response().withStatusCode(401)
        .withBody("{\"error\":\"invalid_client\"}"));

    var client = tokenClient(3);
    var form = formData();
    assertThatThrownBy(() -> client.getToken(tokenUrl, form))
        .isInstanceOf(OpenTmfClientResponseException.class);
    assertThat(mockServer.retrieveRecordedRequests(tokenRequest())).hasSize(1);
    assertThat(listener.outcomes()).containsExactly(Outcome.FAILED);
  }

  @Test
  void zeroNumRetries_disablesTheStatusRetry() {
    mockServer.when(tokenRequest(), Times.once()).respond(response().withStatusCode(503));
    mockServer.when(tokenRequest()).respond(tokenResponse());

    var client = tokenClient(0);
    var form = formData();
    assertThatThrownBy(() -> client.getToken(tokenUrl, form))
        .isInstanceOf(OpenTmfClientResponseException.class);
    assertThat(mockServer.retrieveRecordedRequests(tokenRequest())).hasSize(1);
  }

  private SyncTokenClientImpl tokenClient(int numRetries) {
    var properties = new ClientProperties();
    properties.setNumRetries(numRetries);
    properties.setRetryWaitDuration(Duration.ofMillis(10));
    properties.setBearerAuth(config);
    return new SyncTokenClientImpl(restClient, properties, config, listener);
  }

  private static HttpResponse tokenResponse() {
    return response().withContentType(MediaType.APPLICATION_JSON).withBody(TOKEN_JSON);
  }

  private static HttpRequest tokenRequest() {
    return request().withMethod("POST").withPath(TOKEN_PATH);
  }

  private static LinkedMultiValueMap<String, String> formData() {
    var form = new LinkedMultiValueMap<String, String>();
    form.add("grant_type", "client_credentials");
    return form;
  }

  private List<String> infoMessages() {
    return logEvents.list.stream()
        .filter(e -> e.getLevel() == Level.INFO)
        .map(ILoggingEvent::getFormattedMessage).toList();
  }
}
