package org.opentmf.client.starter;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import org.opentmf.client.bearer.observe.TokenFetchListener;
import org.opentmf.client.bearer.observe.TokenFetchListener.Outcome;

/**
 * Counts bearer-token mints per client and outcome on the application's {@link MeterRegistry}:
 * {@code opentmf.client.token.fetch{client=<id>, outcome=ok|retried|failed}} (Prometheus:
 * {@code opentmf_client_token_fetch_total}). One increment per mint; cache hits never reach the
 * token endpoint and are not counted.
 */
public class TokenFetchMeters {

  public static final String METER_NAME = "opentmf.client.token.fetch";

  private final MeterRegistry meterRegistry;

  public TokenFetchMeters(MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
  }

  /** A listener whose increments carry the given client id in the {@code client} tag. */
  public TokenFetchListener listenerFor(String clientId) {
    Map<Outcome, Counter> counters = new EnumMap<>(Outcome.class);
    for (Outcome outcome : Outcome.values()) {
      counters.put(outcome, Counter.builder(METER_NAME)
          .tag("client", clientId)
          .tag("outcome", outcome.name().toLowerCase(Locale.ROOT))
          .description("Bearer token mints by outcome for the tagged client")
          .register(meterRegistry));
    }
    return (tokenUrl, outcome) -> counters.get(outcome).increment();
  }
}
