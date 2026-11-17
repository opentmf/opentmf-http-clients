package org.opentmf.client.common.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.opentmf.client.common.model.PeerTextInMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

/**
 * The cap's length in {@link ErrorBodyExtractor}: the overloads without it cut at
 * {@link ErrorBodyExtractor#MAX_BODY_LENGTH}, as in every released version; the overloads with it
 * cut a {@code capped} peer's text and a non-JSON body at the given length, and refuse a length
 * outside {@link ErrorBodyExtractor#MIN_CAP_LENGTH} and {@link ErrorBodyExtractor#MAX_CAP_LENGTH}.
 */
class ErrorBodyExtractorCapLengthTest {

  private static final String DETAIL = "D".repeat(3000);
  private static final String JSON = "{\"detail\":\"" + DETAIL + "\",\"title\":\"Busy\"}";
  private static final String PLAIN = "P".repeat(3000);
  private static final String PREFIX = "HTTP 503 Service Unavailable: ";
  private static final HttpStatus STATUS = HttpStatus.SERVICE_UNAVAILABLE;

  private static byte[] utf8(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void theBoundsAreTheProposedOnes() {
    assertThat(ErrorBodyExtractor.MAX_BODY_LENGTH).isEqualTo(2000);
    assertThat(ErrorBodyExtractor.MIN_CAP_LENGTH).isEqualTo(100);
    assertThat(ErrorBodyExtractor.MAX_CAP_LENGTH).isEqualTo(100_000);
  }

  @ParameterizedTest
  @EnumSource(PeerTextInMessage.class)
  void theDefaultLength_givesTheMessageOfTheOverloadsWithoutIt(PeerTextInMessage value) {
    int cap = ErrorBodyExtractor.MAX_BODY_LENGTH;
    for (String body : new String[] {JSON, PLAIN}) {
      assertThat(ErrorBodyExtractor.extractMessage(STATUS, utf8(body),
          MediaType.APPLICATION_JSON, value, cap))
          .isEqualTo(ErrorBodyExtractor.extractMessage(STATUS, utf8(body),
              MediaType.APPLICATION_JSON, value));
      assertThat(ErrorBodyExtractor.extractMessage(STATUS, body, value, cap))
          .isEqualTo(ErrorBodyExtractor.extractMessage(STATUS, body, value));
    }
  }

  @Test
  void capped_cutsThePeersTextAtTheGivenLength() {
    String expected = PREFIX + DETAIL.substring(0, 300) + "...";
    assertThat(ErrorBodyExtractor.extractMessage(STATUS, utf8(JSON), MediaType.APPLICATION_JSON,
        PeerTextInMessage.CAPPED, 300)).isEqualTo(expected);
    assertThat(ErrorBodyExtractor.extractMessage(STATUS, JSON, PeerTextInMessage.CAPPED, 300))
        .isEqualTo(expected);
  }

  @Test
  void whole_keepsAJsonFieldWhole_whateverTheLength() {
    assertThat(ErrorBodyExtractor.extractMessage(STATUS, JSON, PeerTextInMessage.WHOLE, 300))
        .isEqualTo(PREFIX + DETAIL + " — Busy");
    assertThat(ErrorBodyExtractor.extractMessage(STATUS, JSON, null, 300))
        .isEqualTo(PREFIX + DETAIL + " — Busy");
  }

  @ParameterizedTest
  @EnumSource(value = PeerTextInMessage.class, names = {"WHOLE", "CAPPED"})
  void aNonJsonBody_isCutAtTheGivenLength(PeerTextInMessage value) {
    String expected = PREFIX + PLAIN.substring(0, 300) + "...";
    assertThat(ErrorBodyExtractor.extractMessage(STATUS, utf8(PLAIN), MediaType.TEXT_PLAIN,
        value, 300)).isEqualTo(expected);
    assertThat(ErrorBodyExtractor.extractMessage(STATUS, PLAIN, value, 300)).isEqualTo(expected);
  }

  @Test
  void none_keepsTheStatusOnly_atAnyLength() {
    assertThat(ErrorBodyExtractor.extractMessage(STATUS, PLAIN, PeerTextInMessage.NONE, 300))
        .isEqualTo("HTTP 503 Service Unavailable");
  }

  @ParameterizedTest
  @ValueSource(ints = {100, 300, 2000, 100_000})
  void aLengthWithinTheBounds_isAccepted(int length) {
    assertThat(ErrorBodyExtractor.requireCapLength(length)).isEqualTo(length);
  }

  @ParameterizedTest
  @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 99, 100_001, Integer.MAX_VALUE})
  void aLengthOutsideTheBounds_isRefusedWithANamedMessage(int length) {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> ErrorBodyExtractor.requireCapLength(length))
        .withMessage("peer-text-cap-length must be between 100 and 100000, was " + length);
    assertThatIllegalArgumentException()
        .isThrownBy(() -> ErrorBodyExtractor.extractMessage(STATUS, PLAIN,
            PeerTextInMessage.CAPPED, length));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> ErrorBodyExtractor.extractMessage(STATUS, utf8(PLAIN), null,
            PeerTextInMessage.CAPPED, length));
  }
}
