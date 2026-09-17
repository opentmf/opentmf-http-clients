package org.opentmf.client.bearer.observe;

import java.net.URI;

/**
 * Receives one notification per bearer-token mint, after the fetch has settled. The library's
 * token clients call it with {@link Outcome#OK} when the first attempt succeeded,
 * {@link Outcome#RETRIED} when a later attempt did, and {@link Outcome#FAILED} when the mint
 * threw — whatever the cause. Exactly one notification per {@code getToken} call that reached
 * the token endpoint; cache hits are not reported.
 *
 * <p>Deliberately free of any metrics-library type, so the bearer provider stays usable without
 * Micrometer. The autoconfigure module supplies a Micrometer-backed implementation when a
 * {@code MeterRegistry} bean exists.</p>
 */
@FunctionalInterface
public interface TokenFetchListener {

  /** How a mint ended. */
  enum Outcome {
    /** The first attempt returned a token. */
    OK,
    /** A retry returned a token after an earlier attempt failed. */
    RETRIED,
    /** No token: the mint threw (transport failure after the retry, or a status error). */
    FAILED
  }

  void onTokenFetch(URI tokenUrl, Outcome outcome);

  /** A listener that ignores every notification. */
  static TokenFetchListener noop() {
    return (tokenUrl, outcome) -> {
    };
  }
}
