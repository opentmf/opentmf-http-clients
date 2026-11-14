package org.opentmf.client.bearer.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.opentmf.client.bearer.exception.BearerTokenException;
import org.opentmf.client.bearer.exception.BearerTokenResponseException;
import org.opentmf.client.bearer.exception.BearerTokenResponseException.Reason;
import org.opentmf.client.common.exception.OpenTmfClientNotFoundException;
import org.opentmf.client.common.exception.OpenTmfClientResponseException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

class TokenStatusFailuresTest {

  private static final URI TOKEN_URL = URI.create("http://idp/token");
  private static final String ECHO = "s3cr3t-echo";

  @Test
  void aStatusError_becomesTheTokenCallsFailure_keepingStatusHeadersAndRetryAfter() {
    var headers = new HttpHeaders();
    headers.add("Retry-After", "7");
    var raised = new OpenTmfClientResponseException(HttpStatus.SERVICE_UNAVAILABLE,
        "HTTP 503 Service Unavailable: down for maintenance",
        "{\"error\":\"temporarily_unavailable\",\"client_secret\":\"" + ECHO + "\"}");
    raised.setResponseDetails(headers, Duration.ofSeconds(7));

    var failure = TokenStatusFailures.toTokenFailure(TOKEN_URL, raised);

    assertThat(failure).isExactlyInstanceOf(BearerTokenException.class);
    var tokenFailure = (BearerTokenException) failure;
    assertThat(tokenFailure.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    assertThat(tokenFailure.getHeaders().getFirst("Retry-After")).isEqualTo("7");
    assertThat(tokenFailure.getRetryAfter()).isEqualTo(Duration.ofSeconds(7));
    assertThat(tokenFailure.getTokenUrl()).isEqualTo(TOKEN_URL);
    assertThat(tokenFailure.getOauthError()).isEqualTo("temporarily_unavailable");
    assertThat(tokenFailure.getResponseBody()).isNull();
    assertThat(tokenFailure.getCause()).isNull();
    assertThat(tokenFailure.getMessage())
        .isEqualTo("Bearer token request to http://idp/token answered 503 Service Unavailable "
            + "(error=temporarily_unavailable)")
        .doesNotContain(ECHO)
        .doesNotContain("maintenance");
  }

  @Test
  void aNotFound_isTheTokenCallsFailure_notTheNotFoundType() {
    var raised = new OpenTmfClientNotFoundException(HttpStatus.NOT_FOUND, "HTTP 404", "<html/>");

    var failure = TokenStatusFailures.toTokenFailure(TOKEN_URL, raised);

    assertThat(failure).isExactlyInstanceOf(BearerTokenException.class)
        .isNotInstanceOf(OpenTmfClientNotFoundException.class);
    assertThat(failure.getMessage())
        .isEqualTo("Bearer token request to http://idp/token answered 404 Not Found");
  }

  @Test
  void aTokenFailureAlready_isKeptAsItIs() {
    var unusable = new BearerTokenResponseException(TOKEN_URL, HttpStatus.OK, "access_token",
        Reason.FIELD_MISSING);

    assertThat(TokenStatusFailures.toTokenFailure(TOKEN_URL, unusable)).isSameAs(unusable);
  }

  @Test
  void aStatusWithoutAReasonPhrase_isNamedByItsNumber() {
    var failure = TokenStatusFailures.of(TOKEN_URL, HttpStatusCode.valueOf(599), null, null,
        null);

    assertThat(failure.getMessage())
        .isEqualTo("Bearer token request to http://idp/token answered 599");
    assertThat(failure.getHeaders()).isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"invalid_client", "invalid_grant", "unauthorized_client",
      "unsupported_grant_type", "invalid_scope", "invalid_request", "slow_down",
      "authorization_pending", "invalid_dpop_proof"})
  void aRegisteredErrorCode_isKept(String code) {
    assertThat(TokenStatusFailures.oauthErrorOf("{\"error\":\"" + code + "\"}")).isEqualTo(code);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {
      "<html><body>Bad Gateway</body></html>",
      "not json",
      "[\"invalid_client\"]",
      "{\"error_description\":\"no code\"}",
      "{\"error\":42}",
      "{\"error\":null}",
      "{\"error\":\"Invalid Client\"}",
      "{\"error\":\"s3cr3t-echo!\"}",
      "{\"error\":\"_leading_underscore\"}",
      "{\"error\":\"a_code_that_is_far_too_long_to_be_any_registered_oauth_error_code_at_all\"}"})
  void anythingElse_isNoErrorCode(String body) {
    assertThat(TokenStatusFailures.oauthErrorOf(body)).isNull();
  }
}
