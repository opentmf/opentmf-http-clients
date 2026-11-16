package org.opentmf.client.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.opentmf.client.common.model.PeerTextInMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;

/**
 * {@link PeerTextInMessage} in {@link ErrorBodyExtractor}. The golden cells pin today's message
 * for every body shape: the methods without the argument, {@code WHOLE} and {@code null} all give
 * it. {@code CAPPED} cuts only a peer's text over {@link ErrorBodyExtractor#MAX_BODY_LENGTH};
 * {@code NONE} keeps the status and reason only.
 */
class ErrorBodyExtractorPeerTextTest {

  private static final int CAP = ErrorBodyExtractor.MAX_BODY_LENGTH;
  private static final String LONG = "x".repeat(CAP + 1000);
  /** A non-string error field over the cap; Jackson writes its JSON text compact. */
  private static final String ERROR_OBJECT = IntStream.range(0, 300)
      .mapToObj(i -> "\"entry-%04d\"".formatted(i))
      .collect(Collectors.joining(",", "{\"items\":[", "]}"));
  private static final byte[] BINARY = {0x00, 0x01, 0x02, (byte) 0xFF, (byte) 0xFE};

  private static byte[] utf8(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  /** Each body shape with today's message, the golden. */
  static Stream<Arguments> golden() {
    return Stream.of(
        Arguments.of("string field", HttpStatus.NOT_FOUND,
            utf8("{\"detail\":\"Party 123 does not exist\",\"title\":\"Not Found\"}"),
            "HTTP 404 Not Found: Party 123 does not exist — Not Found"),
        Arguments.of("string field, numeric status skipped", HttpStatus.BAD_REQUEST,
            utf8("{\"message\":\"bad input\",\"status\":\"400\",\"code\":\"E42\"}"),
            "HTTP 400 Bad Request: bad input — E42"),
        Arguments.of("long string field", HttpStatus.BAD_REQUEST,
            utf8("{\"detail\":\"" + LONG + "\",\"title\":\"Bad Thing\"}"),
            "HTTP 400 Bad Request: " + LONG + " — Bad Thing"),
        Arguments.of("object field", HttpStatus.INTERNAL_SERVER_ERROR,
            utf8("{\"error\":{\"code\":\"E1\",\"trace\":[\"a\",\"b\"]}}"),
            "HTTP 500 Internal Server Error: {\"code\":\"E1\",\"trace\":[\"a\",\"b\"]}"),
        Arguments.of("large object field", HttpStatus.SERVICE_UNAVAILABLE,
            utf8("{\"error\":" + ERROR_OBJECT + "}"),
            "HTTP 503 Service Unavailable: " + ERROR_OBJECT),
        Arguments.of("object without known fields", HttpStatus.BAD_REQUEST,
            utf8("{\"foo\":\"bar\"}"),
            "HTTP 400 Bad Request: {\"foo\":\"bar\"}"),
        Arguments.of("array body", HttpStatus.BAD_REQUEST,
            utf8("[{\"detail\":\"x\"}]"),
            "HTTP 400 Bad Request: [{\"detail\":\"x\"}]"),
        Arguments.of("non-JSON", HttpStatus.BAD_GATEWAY,
            utf8("  upstream exploded \n"),
            "HTTP 502 Bad Gateway: upstream exploded"),
        Arguments.of("long non-JSON", HttpStatus.BAD_GATEWAY,
            utf8(LONG),
            "HTTP 502 Bad Gateway: " + LONG.substring(0, CAP) + "..."),
        Arguments.of("binary", HttpStatus.BAD_GATEWAY,
            BINARY,
            "HTTP 502 Bad Gateway: " + ErrorBodyExtractor.NON_TEXT_BODY),
        Arguments.of("empty", HttpStatus.NOT_FOUND,
            new byte[0],
            "HTTP 404 Not Found"),
        Arguments.of("absent", HttpStatus.NOT_FOUND,
            null,
            "HTTP 404 Not Found"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("golden")
  void whole_isTodaysMessage(String shape, HttpStatusCode status, byte[] body, String today) {
    assertThat(ErrorBodyExtractor.extractMessage(status, body)).as(shape).isEqualTo(today);
    assertThat(ErrorBodyExtractor.extractMessage(status, body, MediaType.APPLICATION_JSON))
        .isEqualTo(today);
    assertThat(ErrorBodyExtractor.extractMessage(status, body, MediaType.APPLICATION_JSON,
        PeerTextInMessage.WHOLE)).isEqualTo(today);
    assertThat(ErrorBodyExtractor.extractMessage(status, body, null, null)).isEqualTo(today);
    if (body != BINARY) {
      String text = body == null ? null : new String(body, StandardCharsets.UTF_8);
      assertThat(ErrorBodyExtractor.extractMessage(status, text)).isEqualTo(today);
      assertThat(ErrorBodyExtractor.extractMessage(status, text, PeerTextInMessage.WHOLE))
          .isEqualTo(today);
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("golden")
  void none_isTheStatusAndReasonOnly(String shape, HttpStatusCode status, byte[] body,
      String today) {
    String statusOnly = ErrorBodyExtractor.formatStatus(status);
    assertThat(ErrorBodyExtractor.extractMessage(status, body, MediaType.APPLICATION_JSON,
        PeerTextInMessage.NONE)).as(shape).isEqualTo(statusOnly);
    if (body != BINARY) {
      String text = body == null ? null : new String(body, StandardCharsets.UTF_8);
      assertThat(ErrorBodyExtractor.extractMessage(status, text, PeerTextInMessage.NONE))
          .isEqualTo(statusOnly);
    }
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("golden")
  void capped_neverCarriesMoreThanTheCap_andLeavesAShorterMessageAsToday(String shape,
      HttpStatusCode status, byte[] body, String today) {
    String capped = ErrorBodyExtractor.extractMessage(status, body, null,
        PeerTextInMessage.CAPPED);
    String prefix = ErrorBodyExtractor.formatStatus(status) + ": ";
    if (today.length() <= prefix.length() + CAP) {
      assertThat(capped).as(shape).isEqualTo(today);
    } else {
      assertThat(capped).as(shape).hasSize(prefix.length() + CAP + "...".length())
          .isEqualTo(today.substring(0, prefix.length() + CAP) + "...");
    }
  }

  @Test
  void capped_cutsALongStringField_andTheTitleJoinedAfterIt() {
    String body = "{\"detail\":\"" + LONG + "\",\"title\":\"Bad Thing\"}";
    assertThat(ErrorBodyExtractor.extractMessage(HttpStatus.BAD_REQUEST, body,
        PeerTextInMessage.CAPPED))
        .isEqualTo("HTTP 400 Bad Request: " + LONG.substring(0, CAP) + "...");
  }

  @Test
  void capped_cutsALargeObjectField() {
    assertThat(ERROR_OBJECT).hasSizeGreaterThan(CAP);
    assertThat(ErrorBodyExtractor.extractMessage(HttpStatus.SERVICE_UNAVAILABLE,
        utf8("{\"error\":" + ERROR_OBJECT + "}"), MediaType.APPLICATION_JSON,
        PeerTextInMessage.CAPPED))
        .isEqualTo("HTTP 503 Service Unavailable: " + ERROR_OBJECT.substring(0, CAP) + "...");
  }

  @Test
  void capped_appliesToTheJoinedText() {
    String detail = "d".repeat(CAP - 5);
    String body = "{\"detail\":\"" + detail + "\",\"title\":\"Bad Thing\"}";
    assertThat(ErrorBodyExtractor.extractMessage(HttpStatus.BAD_REQUEST, body,
        PeerTextInMessage.CAPPED))
        .isEqualTo("HTTP 400 Bad Request: " + (detail + " — Bad Thing").substring(0, CAP)
            + "...");
  }

  @Test
  void capped_keepsAPeerTextOfExactlyTheCap() {
    String detail = "d".repeat(CAP);
    assertThat(ErrorBodyExtractor.extractMessage(HttpStatus.BAD_REQUEST,
        "{\"detail\":\"" + detail + "\"}", PeerTextInMessage.CAPPED))
        .isEqualTo("HTTP 400 Bad Request: " + detail);
  }

  @ParameterizedTest
  @EnumSource(PeerTextInMessage.class)
  void anUnknownStatus_keepsItsPrefixInEveryValue(PeerTextInMessage peerText) {
    var status = HttpStatusCode.valueOf(599);
    assertThat(ErrorBodyExtractor.extractMessage(status, "{\"detail\":\"d\"}", peerText))
        .startsWith("HTTP 599 Unknown Status");
  }
}
