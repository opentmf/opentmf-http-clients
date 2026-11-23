package org.opentmf.client.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class InvalidTokenRefusalsTest {

  @ParameterizedTest
  @ValueSource(strings = {
      "Bearer error=\"invalid_token\"",
      "Bearer realm=\"api\", error=\"invalid_token\", error_description=\"expired\"",
      "bearer error=invalid_token",
      "Bearer  error = \"INVALID_TOKEN\" ",
      "Basic realm=\"x\", Bearer error=\"invalid_token\""})
  void a401WithTheMarker_namesTheRefusedToken(String challenge) {
    assertThat(InvalidTokenRefusals.refusedToken(401, List.of(challenge), "Bearer abc"))
        .contains("abc");
  }

  @Test
  void theMarker_inASecondHeaderValue_counts() {
    assertThat(InvalidTokenRefusals.refusedToken(401,
        List.of("Basic realm=\"x\"", "Bearer error=\"invalid_token\""), "bearer abc"))
        .contains("abc");
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "Bearer realm=\"api\"",
      "Bearer error=\"insufficient_scope\"",
      "Bearer error=\"invalid_token_type\"",
      "Bearer error_description=\"invalid_token\"",
      "Basic error=\"invalid_token\"",
      "XBearer error=\"invalid_token\""})
  void a401WithoutTheMarker_namesNothing(String challenge) {
    assertThat(InvalidTokenRefusals.refusedToken(401, List.of(challenge), "Bearer abc")).isEmpty();
  }

  @Test
  void anotherStatus_orNoChallenge_orNoBearerToken_namesNothing() {
    var marker = List.of("Bearer error=\"invalid_token\"");
    assertThat(InvalidTokenRefusals.refusedToken(403, marker, "Bearer abc")).isEmpty();
    assertThat(InvalidTokenRefusals.refusedToken(401, null, "Bearer abc")).isEmpty();
    assertThat(InvalidTokenRefusals.refusedToken(401, List.of(), "Bearer abc")).isEmpty();
    assertThat(InvalidTokenRefusals.refusedToken(401, marker, null)).isEmpty();
    assertThat(InvalidTokenRefusals.refusedToken(401, marker, "Basic dXNlcjpwYXNz")).isEmpty();
    assertThat(InvalidTokenRefusals.refusedToken(401, marker, "Bearer ")).isEmpty();
    assertThat(InvalidTokenRefusals.refusedToken(401, Arrays.asList((String) null),
        "Bearer abc")).isEmpty();
  }
}
