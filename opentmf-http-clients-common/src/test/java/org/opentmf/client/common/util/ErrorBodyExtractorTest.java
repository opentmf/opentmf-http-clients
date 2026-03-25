package org.opentmf.client.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

class ErrorBodyExtractorTest {

  @Test
  void extractMessage_nullBody_returnsStatusOnly() {
    String msg = ErrorBodyExtractor.extractMessage(HttpStatus.NOT_FOUND, (byte[]) null);
    assertThat(msg).isEqualTo("HTTP 404 Not Found");
  }

  @Test
  void extractMessage_emptyBody_returnsStatusOnly() {
    String msg = ErrorBodyExtractor.extractMessage(HttpStatus.NOT_FOUND, new byte[0]);
    assertThat(msg).isEqualTo("HTTP 404 Not Found");
  }

  @Test
  void extractMessage_emptyStringBody_returnsStatusOnly() {
    String msg = ErrorBodyExtractor.extractMessage(HttpStatus.NOT_FOUND, "");
    assertThat(msg).isEqualTo("HTTP 404 Not Found");
  }

  @Test
  void extractMessage_plainTextBody() {
    String body = "Something went wrong";
    String msg = ErrorBodyExtractor.extractMessage(HttpStatus.INTERNAL_SERVER_ERROR,
        body.getBytes(StandardCharsets.UTF_8));
    assertThat(msg).isEqualTo("HTTP 500 Internal Server Error: Something went wrong");
  }

  @Test
  void extractMessage_rfc7807_extractsDetail() {
    String body = """
        {"type":"about:blank","title":"Not Found","status":404,"detail":"Resource 42 not found"}""";
    String msg = ErrorBodyExtractor.extractMessage(HttpStatus.NOT_FOUND,
        body.getBytes(StandardCharsets.UTF_8));
    assertThat(msg).contains("Resource 42 not found");
    assertThat(msg).contains("Not Found");
  }

  @Test
  void extractMessage_oauth2_extractsErrorDescription() {
    String body = """
        {"error":"invalid_grant","error_description":"Bad credentials"}""";
    String msg = ErrorBodyExtractor.extractMessage(HttpStatus.UNAUTHORIZED,
        body.getBytes(StandardCharsets.UTF_8));
    assertThat(msg).contains("Bad credentials");
  }

  @Test
  void extractMessage_springBoot_extractsMessage() {
    String body = """
        {"timestamp":"2025-01-01","status":400,"error":"Bad Request","message":"Validation failed","path":"/api"}""";
    String msg = ErrorBodyExtractor.extractMessage(HttpStatus.BAD_REQUEST,
        body.getBytes(StandardCharsets.UTF_8));
    assertThat(msg).contains("Validation failed");
  }

  @Test
  void extractMessage_stringOverload() {
    String msg = ErrorBodyExtractor.extractMessage(HttpStatus.BAD_REQUEST,
        "{\"detail\":\"Invalid input\"}");
    assertThat(msg).contains("Invalid input");
  }

  @Test
  void extractMessage_binaryBody_returnsNonTextMarker() {
    byte[] binary = new byte[]{0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x0B};
    String msg = ErrorBodyExtractor.extractMessage(HttpStatus.BAD_REQUEST, binary);
    assertThat(msg).contains(ErrorBodyExtractor.NON_TEXT_BODY);
  }

  @Test
  void extractMessage_longBody_isTruncated() {
    String longBody = "x".repeat(ErrorBodyExtractor.MAX_BODY_LENGTH + 500);
    String msg = ErrorBodyExtractor.extractMessage(HttpStatus.BAD_REQUEST,
        longBody.getBytes(StandardCharsets.UTF_8));
    assertThat(msg).endsWith("...");
    assertThat(msg.length()).isLessThan(
        ErrorBodyExtractor.MAX_BODY_LENGTH + 100 + "HTTP 400 Bad Request: ".length());
  }

  @Test
  void extractMessage_jsonWithOnlyNumericStatusCode_doesNotIncludeAsTitle() {
    String body = """
        {"status":404,"detail":"item not found"}""";
    String msg = ErrorBodyExtractor.extractMessage(HttpStatus.NOT_FOUND,
        body.getBytes(StandardCharsets.UTF_8));
    assertThat(msg).contains("item not found");
    // "404" alone should not appear as a title part
    assertThat(msg).doesNotContain(" — 404");
  }

  @Test
  void formatStatus_knownCode() {
    assertThat(ErrorBodyExtractor.formatStatus(HttpStatus.OK)).isEqualTo("HTTP 200 OK");
    assertThat(ErrorBodyExtractor.formatStatus(HttpStatus.NOT_FOUND))
        .isEqualTo("HTTP 404 Not Found");
  }

  @Test
  void formatStatus_unknownCode() {
    assertThat(ErrorBodyExtractor.formatStatus(HttpStatusCode.valueOf(999)))
        .isEqualTo("HTTP 999 Unknown Status");
  }

  @Test
  void decodeAsText_nullOrEmpty() {
    assertThat(ErrorBodyExtractor.decodeAsText(null)).isNull();
    assertThat(ErrorBodyExtractor.decodeAsText(new byte[0])).isNull();
  }

  @Test
  void decodeAsText_validUtf8() {
    assertThat(ErrorBodyExtractor.decodeAsText("hello".getBytes(StandardCharsets.UTF_8)))
        .isEqualTo("hello");
  }

  @Test
  void decodeAsText_invalidUtf8_returnsNull() {
    byte[] invalid = new byte[]{(byte) 0xC0, (byte) 0xAF};
    assertThat(ErrorBodyExtractor.decodeAsText(invalid)).isNull();
  }
}
