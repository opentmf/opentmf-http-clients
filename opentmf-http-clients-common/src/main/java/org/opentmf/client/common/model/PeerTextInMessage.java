package org.opentmf.client.common.model;

/**
 * How much of a peer's error text the message of an {@code OpenTmfClientResponseException}
 * carries ({@code opentmf.http-clients.<clientId>.peer-text-in-message}). The exception's
 * {@code getResponseBody()}, status, headers, {@code Retry-After} and its 404 subtype are the same
 * in every value; only {@code getMessage()} differs.
 *
 * <ul>
 *   <li>{@link #WHOLE} — the default and the behaviour of every earlier release: the error field
 *       of a JSON body is taken whole, whatever its length.</li>
 *   <li>{@link #CAPPED} — the peer's text is cut at the client's {@code peer-text-cap-length}
 *       characters (default {@code ErrorBodyExtractor.MAX_BODY_LENGTH}) followed by
 *       {@code ...}.</li>
 *   <li>{@link #NONE} — the message is {@code HTTP <status> <reason>} only.</li>
 * </ul>
 */
public enum PeerTextInMessage {
  WHOLE,
  CAPPED,
  NONE
}
