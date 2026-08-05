package org.opentmf.client.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

/**
 * Covers the {@code Content-Type}-aware overloads of {@link ErrorBodyExtractor}. The header only
 * <em>informs</em> decoding — the trial-parse fallback must stay intact, because servers mislabel
 * error bodies routinely.
 */
class ErrorBodyExtractorContentTypeTest {

  private static final Charset ISO = StandardCharsets.ISO_8859_1;

  @Test
  void decodeAsText_usesTheGivenCharset() {
    byte[] body = "café".getBytes(ISO);

    assertThat(ErrorBodyExtractor.decodeAsText(body, ISO)).isEqualTo("café");
  }

  @Test
  void decodeAsText_nullCharset_fallsBackToUtf8() {
    byte[] body = "café".getBytes(StandardCharsets.UTF_8);

    assertThat(ErrorBodyExtractor.decodeAsText(body, null)).isEqualTo("café");
  }

  @Test
  void extractMessage_usesCharsetFromContentType() {
    byte[] body = "{\"detail\":\"café fermé\"}".getBytes(ISO);
    var contentType = new MediaType("application", "json", ISO);

    var message = ErrorBodyExtractor.extractMessage(HttpStatus.BAD_REQUEST, body, contentType);

    assertThat(message).isEqualTo("HTTP 400 Bad Request: café fermé");
  }

  @Test
  void extractMessage_mislabelledJson_isStillParsed() {
    // A vendor serving JSON as text/html must not defeat message extraction.
    byte[] body = "{\"detail\":\"quota exceeded\"}".getBytes(StandardCharsets.UTF_8);

    var message = ErrorBodyExtractor.extractMessage(
        HttpStatus.TOO_MANY_REQUESTS, body, MediaType.TEXT_HTML);

    assertThat(message).isEqualTo("HTTP 429 Too Many Requests: quota exceeded");
  }

  @Test
  void extractMessage_htmlLabelledAsJson_fallsBackToRawText() {
    byte[] body = "<html>Gateway Error</html>".getBytes(StandardCharsets.UTF_8);

    var message = ErrorBodyExtractor.extractMessage(
        HttpStatus.BAD_GATEWAY, body, MediaType.APPLICATION_JSON);

    assertThat(message).isEqualTo("HTTP 502 Bad Gateway: <html>Gateway Error</html>");
  }

  @Test
  void extractMessage_nullContentType_matchesLegacyOverload() {
    byte[] body = "{\"message\":\"boom\"}".getBytes(StandardCharsets.UTF_8);

    assertThat(ErrorBodyExtractor.extractMessage(HttpStatus.INTERNAL_SERVER_ERROR, body, null))
        .isEqualTo(ErrorBodyExtractor.extractMessage(HttpStatus.INTERNAL_SERVER_ERROR, body));
  }

  @Test
  void extractMessage_emptyBody_withContentType() {
    assertThat(ErrorBodyExtractor.extractMessage(
        HttpStatus.NOT_FOUND, new byte[0], MediaType.APPLICATION_JSON))
        .isEqualTo("HTTP 404 Not Found");
  }

  @Test
  void extractMessage_binaryBody_withContentType() {
    byte[] body = {0, 1, 2, 3, 4, 5, 6, 7};

    assertThat(ErrorBodyExtractor.extractMessage(
        HttpStatus.BAD_REQUEST, body, MediaType.APPLICATION_OCTET_STREAM))
        .isEqualTo("HTTP 400 Bad Request: " + ErrorBodyExtractor.NON_TEXT_BODY);
  }

  @Test
  void charsetOf_returnsNull_whenContentTypeHasNoCharset() {
    assertThat(ErrorBodyExtractor.charsetOf(MediaType.APPLICATION_JSON)).isNull();
    assertThat(ErrorBodyExtractor.charsetOf(null)).isNull();
  }

  @Test
  void charsetOf_returnsDeclaredCharset() {
    assertThat(ErrorBodyExtractor.charsetOf(new MediaType("text", "plain", ISO))).isEqualTo(ISO);
  }

  @Test
  void contentTypeOf_readsTheHeader() {
    var headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);

    assertThat(ErrorBodyExtractor.contentTypeOf(headers)).isEqualTo(MediaType.APPLICATION_JSON);
  }

  @Test
  void contentTypeOf_malformedHeader_isNullRatherThanThrowing() {
    // Throwing here would replace the server's real error with a parsing failure.
    var headers = new HttpHeaders();
    headers.set(HttpHeaders.CONTENT_TYPE, "not/a/valid/type;;;");

    assertThat(ErrorBodyExtractor.contentTypeOf(headers)).isNull();
  }

  @Test
  void contentTypeOf_absentHeaderOrNullHeaders_isNull() {
    assertThat(ErrorBodyExtractor.contentTypeOf(new HttpHeaders())).isNull();
    assertThat(ErrorBodyExtractor.contentTypeOf(null)).isNull();
  }
}
