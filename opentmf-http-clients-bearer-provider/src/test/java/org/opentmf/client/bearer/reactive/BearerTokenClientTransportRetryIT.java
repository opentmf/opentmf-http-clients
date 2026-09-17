package org.opentmf.client.bearer.reactive;

import static org.assertj.core.api.Assertions.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;
import static org.mockserver.model.HttpError.error;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
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
import org.opentmf.client.bearer.exception.BearerTokenException;
import org.opentmf.client.bearer.exception.BearerTokenTransportException;
import org.opentmf.client.bearer.observe.RecordingTokenFetchListener;
import org.opentmf.client.bearer.observe.TokenFetchListener.Outcome;
import org.opentmf.client.common.model.BearerAuthConfig;
import org.opentmf.client.common.model.ClientProperties;
import org.slf4j.LoggerFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.test.StepVerifier;

/**
 * The reactive twin of {@code SyncTokenClientTransportRetryIT}: the token endpoint drops the
 * connection under the mint — at the body stage and at the headers stage — and the client
 * retries exactly once, then reports the outcome.
 */
class BearerTokenClientTransportRetryIT {

  private static final String TOKEN_PATH = "/realms/realm1/protocol/openid-connect/token";
  private static final String TOKEN_JSON = "{\"access_token\":\"tok-1\",\"expires_in\":300}";
  private static final byte[] HEADERS_THEN_NOTHING = """
      HTTP/1.1 200 OK\r
      Content-Type: application/json\r
      Content-Length: 200\r
      \r
      """.getBytes(StandardCharsets.US_ASCII);

  private static ClientAndServer mockServer;
  private static URI tokenUrl;

  private RecordingTokenFetchListener listener;
  private BearerTokenClientImpl tokenClient;
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

    var config = new BearerAuthConfig();
    config.setTokenUrl(tokenUrl);
    config.setClientId("client1");
    config.setClientSecret("client1Secret");
    config.setFormData(Map.of("grant_type", "client_credentials"));

    // no status-based retries, so every attempt seen here is the transport retry's
    var properties = new ClientProperties();
    properties.setNumRetries(0);

    tokenClient = new BearerTokenClientImpl(properties, config, WebClient.builder().build(),
        listener);

    logEvents = new ListAppender<>();
    logEvents.start();
    ((Logger) LoggerFactory.getLogger(BearerTokenClientImpl.class)).addAppender(logEvents);
  }

  @AfterEach
  void tearDown() {
    ((Logger) LoggerFactory.getLogger(BearerTokenClientImpl.class)).detachAppender(logEvents);
  }

  @Test
  void bodyStageDrop_isRetriedOnce_andSucceeds() {
    mockServer.when(tokenRequest(), Times.once())
        .error(error().withResponseBytes(HEADERS_THEN_NOTHING).withDropConnection(true));
    mockServer.when(tokenRequest()).respond(tokenResponse());

    StepVerifier.create(tokenClient.retrieveToken(tokenUrl, formData()))
        .assertNext(token -> assertThat(token.get("access_token").asString()).isEqualTo("tok-1"))
        .verifyComplete();

    assertThat(mockServer.retrieveRecordedRequests(tokenRequest())).hasSize(2);
    assertThat(listener.outcomes()).containsExactly(Outcome.RETRIED);
    assertThat(warnMessages()).singleElement(as(STRING))
        .contains("failed at the transport level", tokenUrl.toString(), "retrying once");
    assertThat(infoMessages()).singleElement(as(STRING))
        .contains("minted from " + tokenUrl, "attempt 2");
  }

  @Test
  void headersStageDrop_isRetriedOnce_andSucceeds() {
    mockServer.when(tokenRequest(), Times.once()).error(error().withDropConnection(true));
    mockServer.when(tokenRequest()).respond(tokenResponse());

    StepVerifier.create(tokenClient.retrieveToken(tokenUrl, formData()))
        .assertNext(token -> assertThat(token.get("access_token").asString()).isEqualTo("tok-1"))
        .verifyComplete();

    assertThat(mockServer.retrieveRecordedRequests(tokenRequest())).hasSize(2);
    assertThat(listener.outcomes()).containsExactly(Outcome.RETRIED);
    assertThat(warnMessages()).hasSize(1);
  }

  @Test
  void twoDrops_failWithTypedTransportException_afterExactlyTwoAttempts() {
    mockServer.when(tokenRequest(), Times.exactly(2))
        .error(error().withResponseBytes(HEADERS_THEN_NOTHING).withDropConnection(true));
    mockServer.when(tokenRequest()).respond(tokenResponse());

    StepVerifier.create(tokenClient.retrieveToken(tokenUrl, formData()))
        .expectErrorSatisfies(e -> {
          assertThat(e).isInstanceOf(BearerTokenTransportException.class);
          var ex = (BearerTokenTransportException) e;
          assertThat(ex.getTokenUrl()).isEqualTo(tokenUrl);
          assertThat(ex.getAttempts()).isEqualTo(2);
          assertThat(ex).rootCause().isInstanceOf(IOException.class);
        })
        .verify(Duration.ofSeconds(10));

    assertThat(mockServer.retrieveRecordedRequests(tokenRequest())).hasSize(2);
    assertThat(listener.outcomes()).containsExactly(Outcome.FAILED);
    assertThat(warnMessages()).hasSize(1);
    assertThat(infoMessages()).isEmpty();
  }

  @Test
  void statusError_isNotRetried_andPassesThroughUnchanged() {
    mockServer.when(tokenRequest()).respond(response().withStatusCode(401)
        .withContentType(MediaType.APPLICATION_JSON).withBody("{\"error\":\"invalid_client\"}"));

    StepVerifier.create(tokenClient.retrieveToken(tokenUrl, formData()))
        .expectError(BearerTokenException.class)
        .verify(Duration.ofSeconds(10));

    assertThat(mockServer.retrieveRecordedRequests(tokenRequest())).hasSize(1);
    assertThat(listener.outcomes()).containsExactly(Outcome.FAILED);
    assertThat(warnMessages()).isEmpty();
  }

  @Test
  void firstAttemptOk_reportsOk_andLogsOneMintLine() {
    mockServer.when(tokenRequest()).respond(tokenResponse());

    StepVerifier.create(tokenClient.retrieveToken(tokenUrl, formData()))
        .assertNext(token -> assertThat(token.get("access_token").asString()).isEqualTo("tok-1"))
        .verifyComplete();

    assertThat(mockServer.retrieveRecordedRequests(tokenRequest())).hasSize(1);
    assertThat(listener.notifications()).singleElement()
        .satisfies(n -> {
          assertThat(n.tokenUrl()).isEqualTo(tokenUrl);
          assertThat(n.outcome()).isEqualTo(Outcome.OK);
        });
    assertThat(infoMessages()).singleElement(as(STRING))
        .contains("minted from " + tokenUrl, "attempt 1");
    assertThat(warnMessages()).isEmpty();
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

  private List<String> warnMessages() {
    return logEvents.list.stream()
        .filter(e -> e.getLevel() == Level.WARN)
        .map(ILoggingEvent::getFormattedMessage).toList();
  }

  private List<String> infoMessages() {
    return logEvents.list.stream()
        .filter(e -> e.getLevel() == Level.INFO)
        .map(ILoggingEvent::getFormattedMessage).toList();
  }
}
