package org.opentmf.client.common.util;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.Generated;
import org.jspecify.annotations.Nullable;
import org.opentmf.client.common.model.PeerTextInMessage;
import org.opentmf.commons.util.JacksonUtil;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import tools.jackson.databind.JsonNode;

/**
 * Extracts a human-readable error message from HTTP error response bodies. Supports multiple
 * common JSON error formats (RFC 7807, TMF Open API, OAuth2, Spring Boot) and falls back
 * gracefully for plain text, binary, or empty bodies.
 *
 * <p>The methods without a {@link PeerTextInMessage} argument give the
 * {@link PeerTextInMessage#WHOLE} message, the behaviour of earlier releases. The methods without
 * a cap length cut at {@link #MAX_BODY_LENGTH}, as every earlier release did.</p>
 */
public final class ErrorBodyExtractor {

  /** The default cap length: where a non-JSON body, and a {@code capped} peer's text, is cut. */
  public static final int MAX_BODY_LENGTH = 2000;
  /** The shortest cap length a client may set ({@code peer-text-cap-length}). */
  public static final int MIN_CAP_LENGTH = 100;
  /** The longest cap length a client may set ({@code peer-text-cap-length}). */
  public static final int MAX_CAP_LENGTH = 100_000;
  public static final String NON_TEXT_BODY = "[non-text response body]";

  private static final List<String> MESSAGE_FIELDS = List.of(
      "detail",            // RFC 7807 Problem Details
      "error_description", // OAuth2
      "message",           // Spring Boot / generic
      "reason",            // TMF Open API
      "error",             // OAuth2 / generic
      "msg",               // common alternative
      "description"        // common alternative
  );

  private static final List<String> TITLE_FIELDS = List.of(
      "title",             // RFC 7807
      "status",            // TMF Open API (textual status)
      "code"               // TMF Open API / generic
  );

  @Generated
  private ErrorBodyExtractor() {
  }

  /**
   * Extracts a human-readable error message from the response body bytes.
   *
   * @return a formatted message like {@code "HTTP 404 Not Found: Resource not found"}
   */
  public static String extractMessage(HttpStatusCode status, byte[] body) {
    return extractMessage(status, body, null);
  }

  /**
   * Extracts a human-readable error message, using {@code contentType} to pick the charset.
   *
   * <p>The content type only <em>informs</em> decoding — the JSON probe still runs on whatever
   * decodes successfully, because servers mislabel error bodies routinely (JSON served as
   * {@code text/html}, or a proxy's HTML error page served as {@code application/json}).</p>
   *
   * @param contentType the response {@code Content-Type}, or {@code null} if absent
   * @return a formatted message like {@code "HTTP 404 Not Found: Resource not found"}
   */
  public static String extractMessage(HttpStatusCode status, byte[] body,
      @Nullable MediaType contentType) {
    return extractMessage(status, body, contentType, PeerTextInMessage.WHOLE);
  }

  /**
   * Extracts the message, carrying as much of the peer's text as {@code peerText} allows.
   *
   * @param contentType the response {@code Content-Type}, or {@code null} if absent
   * @param peerText how much of the peer's text the message carries; {@code null} means
   *     {@link PeerTextInMessage#WHOLE}
   * @return a formatted message like {@code "HTTP 404 Not Found: Resource not found"}, or
   *     {@code "HTTP 404 Not Found"} under {@link PeerTextInMessage#NONE}
   */
  public static String extractMessage(HttpStatusCode status, byte[] body,
      @Nullable MediaType contentType, @Nullable PeerTextInMessage peerText) {
    return extractMessage(status, body, contentType, peerText, MAX_BODY_LENGTH);
  }

  /**
   * Extracts the message, carrying as much of the peer's text as {@code peerText} allows and
   * cutting at {@code capLength} where it cuts.
   *
   * @param contentType the response {@code Content-Type}, or {@code null} if absent
   * @param peerText how much of the peer's text the message carries; {@code null} means
   *     {@link PeerTextInMessage#WHOLE}
   * @param capLength where a non-JSON body, and a {@link PeerTextInMessage#CAPPED} peer's text,
   *     is cut; see {@link #requireCapLength(int)}
   * @throws IllegalArgumentException when {@code capLength} is outside the bounds
   */
  public static String extractMessage(HttpStatusCode status, byte[] body,
      @Nullable MediaType contentType, @Nullable PeerTextInMessage peerText, int capLength) {
    requireCapLength(capLength);
    String statusPrefix = formatStatus(status);
    if (body == null || body.length == 0 || peerText == PeerTextInMessage.NONE) {
      return statusPrefix;
    }
    String text = decodeAsText(body, charsetOf(contentType));
    if (text == null) {
      return statusPrefix + ": " + NON_TEXT_BODY;
    }
    return composeMessage(statusPrefix, text, peerText, capLength);
  }

  /**
   * Extracts a human-readable error message from the response body string.
   *
   * @return a formatted message like {@code "HTTP 404 Not Found: Resource not found"}
   */
  public static String extractMessage(HttpStatusCode status, String body) {
    return extractMessage(status, body, PeerTextInMessage.WHOLE);
  }

  /**
   * Extracts the message from the response body string, carrying as much of the peer's text as
   * {@code peerText} allows.
   *
   * @param peerText how much of the peer's text the message carries; {@code null} means
   *     {@link PeerTextInMessage#WHOLE}
   */
  public static String extractMessage(HttpStatusCode status, String body,
      @Nullable PeerTextInMessage peerText) {
    return extractMessage(status, body, peerText, MAX_BODY_LENGTH);
  }

  /**
   * Extracts the message from the response body string, carrying as much of the peer's text as
   * {@code peerText} allows and cutting at {@code capLength} where it cuts.
   *
   * @param peerText how much of the peer's text the message carries; {@code null} means
   *     {@link PeerTextInMessage#WHOLE}
   * @param capLength where a non-JSON body, and a {@link PeerTextInMessage#CAPPED} peer's text,
   *     is cut; see {@link #requireCapLength(int)}
   * @throws IllegalArgumentException when {@code capLength} is outside the bounds
   */
  public static String extractMessage(HttpStatusCode status, String body,
      @Nullable PeerTextInMessage peerText, int capLength) {
    requireCapLength(capLength);
    String statusPrefix = formatStatus(status);
    if (body == null || body.isEmpty() || peerText == PeerTextInMessage.NONE) {
      return statusPrefix;
    }
    return composeMessage(statusPrefix, body, peerText, capLength);
  }

  /**
   * Returns {@code capLength} when it lies between {@link #MIN_CAP_LENGTH} and
   * {@link #MAX_CAP_LENGTH}, both included.
   *
   * @throws IllegalArgumentException naming {@code peer-text-cap-length} otherwise
   */
  public static int requireCapLength(int capLength) {
    if (capLength < MIN_CAP_LENGTH || capLength > MAX_CAP_LENGTH) {
      throw new IllegalArgumentException("peer-text-cap-length must be between " + MIN_CAP_LENGTH
          + " and " + MAX_CAP_LENGTH + ", was " + capLength);
    }
    return capLength;
  }

  /**
   * Attempts to decode the body as the raw response string, returning {@code null}
   * if the content appears to be binary.
   */
  public static String decodeAsText(byte[] body) {
    return decodeAsText(body, null);
  }

  /**
   * Attempts to decode the body using the given charset, returning {@code null} if the content
   * appears to be binary.
   *
   * @param charset the charset from the response {@code Content-Type}; UTF-8 when {@code null}
   */
  public static String decodeAsText(byte[] body, @Nullable Charset charset) {
    if (body == null || body.length == 0) {
      return null;
    }
    var decoder = (charset == null ? StandardCharsets.UTF_8 : charset).newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT);
    try {
      String decoded = decoder.decode(ByteBuffer.wrap(body)).toString();
      long controlCount = decoded.chars()
          .filter(c -> c < 0x20 && c != '\n' && c != '\r' && c != '\t')
          .count();
      if (controlCount > decoded.length() / 10) {
        return null;
      }
      return decoded;
    } catch (CharacterCodingException e) {
      return null;
    }
  }

  /**
   * The {@code Content-Type} of the given headers, or {@code null} when absent or unparseable.
   *
   * <p>Deliberately total: this runs inside error handlers, where throwing on a malformed
   * {@code Content-Type} would replace the server's actual error with a parsing failure and hide
   * what really went wrong.</p>
   */
  public static @Nullable MediaType contentTypeOf(@Nullable HttpHeaders headers) {
    if (headers == null) {
      return null;
    }
    try {
      return headers.getContentType();
    } catch (RuntimeException e) {
      return null;
    }
  }

  /**
   * The charset named by the content type, or {@code null} to fall back to UTF-8. An unsupported
   * or malformed charset name is treated as absent rather than propagated as an exception.
   */
  public static @Nullable Charset charsetOf(@Nullable MediaType contentType) {
    if (contentType == null) {
      return null;
    }
    try {
      return contentType.getCharset();
    } catch (RuntimeException e) {
      return null;
    }
  }

  public static String formatStatus(HttpStatusCode status) {
    String reasonPhrase = Optional.ofNullable(HttpStatus.resolve(status.value()))
        .map(HttpStatus::getReasonPhrase)
        .orElse("Unknown Status");
    return "HTTP " + status.value() + " " + reasonPhrase;
  }

  private static String composeMessage(String statusPrefix, String body,
      @Nullable PeerTextInMessage peerText, int capLength) {
    String fields = tryExtractFromJson(body);
    if (fields == null) {
      return statusPrefix + ": " + truncate(body.trim(), capLength);
    }
    return statusPrefix + ": "
        + (peerText == PeerTextInMessage.CAPPED ? truncate(fields, capLength) : fields);
  }

  /**
   * The error fields of a JSON object body, joined and whole; {@code null} when the body is not a
   * JSON object or holds none of the fields.
   */
  private static @Nullable String tryExtractFromJson(String body) {
    try {
      JsonNode root = JacksonUtil.getDefaultJsonMapper().readTree(body);
      if (root == null || !root.isObject()) {
        return null;
      }
      var parts = new ArrayList<String>();

      for (String field : MESSAGE_FIELDS) {
        String value = textValue(root, field);
        if (value != null) {
          parts.add(value);
          break;
        }
      }

      for (String field : TITLE_FIELDS) {
        String value = textValue(root, field);
        if (value != null && !isNumericStatusCode(value)) {
          parts.add(value);
          break;
        }
      }

      return parts.isEmpty() ? null : String.join(" — ", parts);
    } catch (Exception e) {
      return null;
    }
  }

  private static String textValue(JsonNode root, String fieldName) {
    JsonNode node = root.get(fieldName);
    if (node == null || node.isNull()) {
      return null;
    }
    String text = node.isString() ? node.asString() : node.toString();
    return text.isBlank() ? null : text.trim();
  }

  private static boolean isNumericStatusCode(String value) {
    if (value.length() != 3) {
      return false;
    }
    for (char c : value.toCharArray()) {
      if (!Character.isDigit(c)) {
        return false;
      }
    }
    return true;
  }

  private static String truncate(String text, int capLength) {
    if (text.length() <= capLength) {
      return text;
    }
    return text.substring(0, capLength) + "...";
  }
}
