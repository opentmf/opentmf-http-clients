package org.opentmf.client.starter.rest;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.opentmf.client.rest.service.api.OutboundInterceptorContribution;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.OrderComparator;
import org.springframework.http.client.ClientHttpRequestInterceptor;

/**
 * {@link OutboundInterceptorContribution} providers for the factory tests, ordered the way
 * Spring's {@code orderedStream()} orders beans that implement {@code Ordered}.
 */
final class ContributionTestSupport {

  static final ObjectProvider<OutboundInterceptorContribution> NONE = provider();

  private ContributionTestSupport() {
  }

  /**
   * An interceptor that notes its name, the request path and the {@code X-Custom} header it
   * sees — a fixed header in the tests, so {@code null} means the fixed-headers interceptor has
   * not run yet.
   */
  static ClientHttpRequestInterceptor recorder(String name, List<String> seen) {
    return (request, body, execution) -> {
      seen.add(name + " " + request.getURI().getPath()
          + " X-Custom=" + request.getHeaders().getFirst("X-Custom"));
      return execution.execute(request, body);
    };
  }

  static ObjectProvider<OutboundInterceptorContribution> provider(
      OutboundInterceptorContribution... contributions) {
    return new ObjectProvider<>() {
      @Override public OutboundInterceptorContribution getObject() {
        throw new UnsupportedOperationException();
      }
      @Override public Stream<OutboundInterceptorContribution> stream() {
        return Arrays.stream(contributions);
      }
      @Override public Stream<OutboundInterceptorContribution> orderedStream() {
        return stream().sorted(OrderComparator.INSTANCE);
      }
    };
  }
}
