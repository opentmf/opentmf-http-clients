package org.opentmf.client.bearer.sync;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.opentmf.client.bearer.util.UnusableTokenResponses.assertNamedError;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.MediaType;
import org.opentmf.client.bearer.exception.BearerTokenResponseException.Reason;
import org.opentmf.client.bearer.model.TokenEntry;
import org.opentmf.client.bearer.observe.RecordingTokenFetchListener;
import org.opentmf.client.bearer.observe.TokenFetchListener.Outcome;
import org.opentmf.client.common.model.BearerAuthConfig;
import org.opentmf.client.common.model.ClientProperties;
import org.opentmf.client.rest.util.OpenTmfRestClientStatusHandler;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;

/**
 * A success answer without a usable token fails with the named
 * {@code BearerTokenResponseException} — endpoint, status and field, never the body — is not
 * retried, and leaves nothing in the cache.
 */
class SyncTokenResponseIT {

  private static final String TOKEN_PATH = "/realms/realm1/protocol/openid-connect/token";

  private static ClientAndServer mockServer;
  private static URI tokenUrl;

  private RecordingTokenFetchListener listener;
  private Cache<String, TokenEntry> tokenCache;
  private SyncBearerTokenServiceImpl tokenService;

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
    tokenCache = Caffeine.newBuilder().expireAfterWrite(Duration.ofMinutes(5)).build();

    var config = new BearerAuthConfig();
    config.setTokenUrl(tokenUrl);
    config.setClientId("client1");
    config.setClientSecret("client1Secret");
    config.setFormData(Map.of("grant_type", "client_credentials"));

    var properties = new ClientProperties();
    properties.setNumRetries(2);
    properties.setRetryWaitDuration(Duration.ofMillis(10));

    var restClient = RestClient.builder()
        .defaultStatusHandler(HttpStatusCode::isError,
            OpenTmfRestClientStatusHandler.errorHandler())
        .build();
    var tokenClient = new SyncTokenClientImpl(restClient, properties, config, listener);
    tokenService = new SyncBearerTokenServiceImpl(config, tokenCache, tokenClient);
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("org.opentmf.client.bearer.util.UnusableTokenResponses#cases")
  void unusableSuccessAnswer_failsWithTheNamedError(String name, HttpResponse answer, int status,
      Reason reason) {
    mockServer.when(tokenRequest()).respond(answer);

    var thrown = catchThrowable(tokenService::getToken);

    assertNamedError(thrown, tokenUrl, status, reason);
    assertThat(mockServer.retrieveRecordedRequests(tokenRequest())).hasSize(1);
    assertThat(listener.outcomes()).containsExactly(Outcome.FAILED);
    assertThat(tokenCache.asMap()).isEmpty();
  }

  @Test
  void aUsableAnswer_isStillMintedAndCached() {
    mockServer.when(tokenRequest()).respond(response().withStatusCode(200)
        .withContentType(MediaType.APPLICATION_JSON)
        .withBody("{\"access_token\":\"tok-1\",\"expires_in\":300}"));

    assertThat(tokenService.getToken()).isEqualTo("tok-1");
    assertThat(tokenService.getToken()).isEqualTo("tok-1");

    assertThat(mockServer.retrieveRecordedRequests(tokenRequest())).hasSize(1);
    assertThat(listener.outcomes()).containsExactly(Outcome.OK);
  }

  private static HttpRequest tokenRequest() {
    return request().withMethod("POST").withPath(TOKEN_PATH);
  }
}
