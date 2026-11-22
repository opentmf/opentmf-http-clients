package org.opentmf.client.common.util;

/**
 * The route name of a bearer token fetch: the {@code uri} label of the token call's client
 * observation (the {@code http.client.requests} metric and its span). A token address is
 * configured, not a URI template, so its path would carry the address; the label is this fixed
 * name instead, the same for every client, so a dashboard tells token fetches from the client's
 * other calls by the {@code uri} label and from each other by the client's own labels.
 *
 * <p>A NAME, not a path: lower-case words joined by a dash, no slash.</p>
 *
 * @since 2.6.0
 */
public final class TokenRoute {

  /** The {@code uri} label of every token fetch. */
  public static final String NAME = "token-fetch";

  private TokenRoute() {
  }
}
