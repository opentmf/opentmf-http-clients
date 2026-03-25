package org.opentmf.client.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.opentmf.client.common.util.ErrorBodyExtractor;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

class ErrorBodyExtractorTest {

  // --- extractMessage with byte[] ---

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
  void extractMessage_plainText_returnsStatusAndText() {
    byte[] body = "something went wrong".getBytes(StandardCharsets.UTF_8);
    String msg = ErrorBodyExtractor.extractMessage(HttpStatus.INTERNAL_SERVER_ERROR, body);
    assertThat(msg).startsWith("HTTP 500 Internal Server Error: ");
    assertThat(msg).contains("something went wrong");
  }

  @Test
  void extractMessage_binaryBody_returnsNonTextPlaceholder() {
    byte[] body = new byte[]{0x00, 0x01, 0x02, (byte) 0xFF, (byte) 0xFE};
    String msg = ErrorBodyExtractor.extractMessage(HttpStatus.BAD_GATEWAY, body);
    assertThat(msg).contains(ErrorBodyExtractor.NON_TEXT_BODY);
  }

  // --- JSON format: RFC 7807 Problem Details ---

  @Test
  void extractMessage_rfc7807_extractsDetailAndTitle() {
    String json = """
        {"type":"about:blank","title":"Not Found","status":404,"detail":"Party 123 does not exist"}""";
    String msg = ErrorBodyExtractor.extractMessage(HttpStatus.NOT_FOUND, json);
    assertThat(msg).contains("Party 123 does not exist");
    assertThat(msg).contains("Not Found");
  }

  // --- JSON format: TMF Open API ---

  @Test
  void extractMessage_tmf_extractsReasonAndMessage() {
    String json = """
        {"code":"404","reason":"Not Found","message":"Resource party/123 does not exist","status":"404"}""";
    String msg = ErrorBodyExtractor.extractMessage(HttpStatus.NOT_FOUND, json);
    assertThat(msg).contains("Resource party/123 does not exist");
    assertThat(msg).contains("Not Found");
  }

  // --- JSON format: OAuth2 ---

  @Test
  void extractMessage_oauth2_extractsErrorDescription() {
    String json = """
        {"error":"invalid_grant","error_description":"The refresh token is expired"}""";
    String msg = ErrorBodyExtractor.extractMessage(HttpStatus.BAD_REQUEST, json);
    assertThat(msg).contains("The refresh token is expired");
  }

  @Test
  void extractMessage_oauth2_errorOnly() {
    String json = """
        {"error":"unauthorized_client"}""";
    String msg = ErrorBodyExtractor.extractMessage(HttpStatus.UNAUTHORIZED, json);
    assertThat(msg).contains("unauthorized_client");
  }

  // --- JSON format: Spring Boot default ---

  @Test
  void extractMessage_springBoot_extractsMessage() {
    String json = """
        {"timestamp":"2025-01-01","status":500,"error":"Internal Server Error","message":"NullPointerException","path":"/api/foo"}""";
    String msg = ErrorBodyExtractor.extractMessage(HttpStatus.INTERNAL_SERVER_ERROR, json);
    assertThat(msg).contains("NullPointerException");
  }

  // --- JSON format: generic ---

  @Test
  void extractMessage_genericMessage_extractsMessage() {
    String json = """
        {"message":"Something failed"}""";
    String msg = ErrorBodyExtractor.extractMessage(HttpStatus.BAD_REQUEST, json);
    assertThat(msg).contains("Something failed");
  }

  @Test
  void extractMessage_unknownJsonFields_returnsRawJson() {
    String json = """
        {"foo":"bar","baz":42}""";
    String msg = ErrorBodyExtractor.extractMessage(HttpStatus.BAD_REQUEST, json);
    assertThat(msg).startsWith("HTTP 400 Bad Request: ");
    assertThat(msg).contains("foo");
  }

  // --- JSON with numeric-only status/code fields ---

  @Test
  void extractMessage_tmf_numericCodeSkipped_reasonExtracted() {
    String json = """
        {"code":"404","reason":"Resource not found"}""";
    String msg = ErrorBodyExtractor.extractMessage(HttpStatus.NOT_FOUND, json);
    assertThat(msg).contains("Resource not found");
    assertThat(msg).doesNotContain(" — 404");
  }

  // --- extractMessage with String ---

  @Test
  void extractMessage_stringNull_returnsStatusOnly() {
    String msg = ErrorBodyExtractor.extractMessage(HttpStatus.NOT_FOUND, (String) null);
    assertThat(msg).isEqualTo("HTTP 404 Not Found");
  }

  @Test
  void extractMessage_stringEmpty_returnsStatusOnly() {
    String msg = ErrorBodyExtractor.extractMessage(HttpStatus.NOT_FOUND, "");
    assertThat(msg).isEqualTo("HTTP 404 Not Found");
  }

  // --- truncation ---

  @Test
  void extractMessage_longBody_isTruncated() {
    String longBody = "x".repeat(ErrorBodyExtractor.MAX_BODY_LENGTH + 500);
    String msg = ErrorBodyExtractor.extractMessage(HttpStatus.BAD_REQUEST, longBody);
    assertThat(msg).endsWith("...");
    assertThat(msg.length()).isLessThan(
        "HTTP 400 Bad Request: ".length() + ErrorBodyExtractor.MAX_BODY_LENGTH + 10);
  }

  // --- decodeAsText ---

  @Test
  void decodeAsText_validUtf8_returnsString() {
    byte[] body = "hello world".getBytes(StandardCharsets.UTF_8);
    assertThat(ErrorBodyExtractor.decodeAsText(body)).isEqualTo("hello world");
  }

  @Test
  void decodeAsText_binary_returnsNull() {
    byte[] body = new byte[]{0x00, 0x01, 0x02, (byte) 0xFF, (byte) 0xFE};
    assertThat(ErrorBodyExtractor.decodeAsText(body)).isNull();
  }

  @Test
  void decodeAsText_null_returnsNull() {
    assertThat(ErrorBodyExtractor.decodeAsText(null)).isNull();
  }

  @Test
  void decodeAsText_empty_returnsNull() {
    assertThat(ErrorBodyExtractor.decodeAsText(new byte[0])).isNull();
  }

  // --- formatStatus ---

  @Test
  void formatStatus_knownStatus() {
    assertThat(ErrorBodyExtractor.formatStatus(HttpStatus.NOT_FOUND))
        .isEqualTo("HTTP 404 Not Found");
  }

  @Test
  void formatStatus_unknownStatus() {
    assertThat(ErrorBodyExtractor.formatStatus(HttpStatusCode.valueOf(999)))
        .isEqualTo("HTTP 999 Unknown Status");
  }
}
