package org.opentmf.client.common.util;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.Generated;
import org.opentmf.commons.util.JacksonUtil;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import tools.jackson.databind.JsonNode;

/**
 * Extracts a human-readable error message from HTTP error response bodies. Supports multiple
 * common JSON error formats (RFC 7807, TMF Open API, OAuth2, Spring Boot) and falls back
 * gracefully for plain text, binary, or empty bodies.
 */
public final class ErrorBodyExtractor {

  public static final int MAX_BODY_LENGTH = 2000;
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
    String statusPrefix = formatStatus(status);
    if (body == null || body.length == 0) {
      return statusPrefix;
    }
    String text = decodeAsText(body);
    if (text == null) {
      return statusPrefix + ": " + NON_TEXT_BODY;
    }
    return composeMessage(statusPrefix, text);
  }

  /**
   * Extracts a human-readable error message from the response body string.
   *
   * @return a formatted message like {@code "HTTP 404 Not Found: Resource not found"}
   */
  public static String extractMessage(HttpStatusCode status, String body) {
    String statusPrefix = formatStatus(status);
    if (body == null || body.isEmpty()) {
      return statusPrefix;
    }
    return composeMessage(statusPrefix, body);
  }

  /**
   * Attempts to decode the body as the raw response string, returning {@code null}
   * if the content appears to be binary.
   */
  public static String decodeAsText(byte[] body) {
    if (body == null || body.length == 0) {
      return null;
    }
    var decoder = StandardCharsets.UTF_8.newDecoder()
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

  public static String formatStatus(HttpStatusCode status) {
    String reasonPhrase = Optional.ofNullable(HttpStatus.resolve(status.value()))
        .map(HttpStatus::getReasonPhrase)
        .orElse("Unknown Status");
    return "HTTP " + status.value() + " " + reasonPhrase;
  }

  private static String composeMessage(String statusPrefix, String body) {
    String jsonMessage = tryExtractFromJson(body);
    if (jsonMessage != null) {
      return statusPrefix + ": " + jsonMessage;
    }
    return statusPrefix + ": " + truncate(body.trim());
  }

  private static String tryExtractFromJson(String body) {
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

      if (!parts.isEmpty()) {
        return String.join(" — ", parts);
      }

      return truncate(body.trim());
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

  private static String truncate(String text) {
    if (text.length() <= MAX_BODY_LENGTH) {
      return text;
    }
    return text.substring(0, MAX_BODY_LENGTH) + "...";
  }
}
