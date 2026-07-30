package org.opentmf.client.starter;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToDoubleFunction;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.pool.PoolStats;

/**
 * Per-client connection-pool gauges for Apache HttpClient 5:
 * {@code opentmf.client.pool.leased|available|pending|max}, tagged {@code client=<name>}.
 * Registered by the Apache factory whenever a client is built; other client types expose no
 * pool and register nothing. Re-registering a name (dynamic client replace) swaps the gauges;
 * {@link #deregister(String)} removes them (dynamic client evict).
 */
public class ApachePoolMeters {

  private final MeterRegistry meterRegistry;
  private final Map<String, List<Meter.Id>> metersByClient = new ConcurrentHashMap<>();

  public ApachePoolMeters(MeterRegistry meterRegistry) {
    this.meterRegistry = meterRegistry;
  }

  public void register(String clientName, PoolingHttpClientConnectionManager connectionManager) {
    deregister(clientName);
    metersByClient.put(clientName, List.of(
        poolGauge("opentmf.client.pool.leased", clientName, connectionManager,
            PoolStats::getLeased),
        poolGauge("opentmf.client.pool.available", clientName, connectionManager,
            PoolStats::getAvailable),
        poolGauge("opentmf.client.pool.pending", clientName, connectionManager,
            PoolStats::getPending),
        poolGauge("opentmf.client.pool.max", clientName, connectionManager,
            PoolStats::getMax)));
  }

  public void deregister(String clientName) {
    var meterIds = metersByClient.remove(clientName);
    if (meterIds != null) {
      meterIds.forEach(meterRegistry::remove);
    }
  }

  private Meter.Id poolGauge(String name, String clientName,
      PoolingHttpClientConnectionManager connectionManager,
      ToDoubleFunction<PoolStats> statExtractor) {
    return Gauge
        .builder(name, connectionManager,
            cm -> statExtractor.applyAsDouble(cm.getTotalStats()))
        .tag("client", clientName)
        .description("Apache HttpClient 5 total pool statistic for the tagged client")
        .register(meterRegistry)
        .getId();
  }
}
