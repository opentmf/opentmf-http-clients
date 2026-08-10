package org.opentmf.client.starter.rest;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.transport.SenderContext;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Observation fixtures for the factory tests. The header-injecting handler emulates what
 * micrometer-tracing's {@code PropagatingSenderTracingObservationHandler} does — write trace
 * context onto the outbound carrier on observation start — without pulling the tracing
 * dependency into this library.
 */
final class ObservationTestSupport {

  static final String TRACEPARENT = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

  private ObservationTestSupport() {
  }

  static ObservationRegistry headerInjectingRegistry() {
    var registry = ObservationRegistry.create();
    registry.observationConfig().observationHandler(new HeaderInjectingHandler());
    return registry;
  }

  static ObjectProvider<ObservationRegistry> provider(ObservationRegistry registry) {
    return new ObjectProvider<>() {
      @Override public ObservationRegistry getObject() { return registry; }
      @Override public ObservationRegistry getIfAvailable() { return registry; }
    };
  }

  private static final class HeaderInjectingHandler
      implements ObservationHandler<SenderContext<Object>> {

    @Override
    public boolean supportsContext(Observation.Context context) {
      return context instanceof SenderContext;
    }

    @Override
    public void onStart(SenderContext<Object> context) {
      Object carrier = context.getCarrier();
      if (carrier != null) {
        context.getSetter().set(carrier, "traceparent", TRACEPARENT);
      }
    }
  }
}
