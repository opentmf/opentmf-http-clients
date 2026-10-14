package org.opentmf.client.rest.service.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.core.OrderComparator;
import org.springframework.core.Ordered;
import org.springframework.http.client.ClientHttpRequestInterceptor;

class OutboundInterceptorContributionTest {

  private static final ClientHttpRequestInterceptor PASS_THROUGH =
      (request, body, execution) -> execution.execute(request, body);

  @Test
  void carriesTheInterceptorAndItsOrder() {
    var contribution = new OutboundInterceptorContribution(PASS_THROUGH, 10);

    assertThat(contribution.interceptor()).isSameAs(PASS_THROUGH);
    assertThat(contribution.order()).isEqualTo(10);
    assertThat(contribution.getOrder()).isEqualTo(10);
  }

  @Test
  void sortsByItsOrder_theWaySpringDoes() {
    var late = new OutboundInterceptorContribution(PASS_THROUGH, 20);
    var early = new OutboundInterceptorContribution(PASS_THROUGH, 10);

    assertThat(Stream.of(late, early).sorted(OrderComparator.INSTANCE))
        .containsExactly(early, late);
  }

  @Test
  void withoutAnOrder_takesSpringsDefaultForAnUnorderedBean() {
    var contribution = new OutboundInterceptorContribution(PASS_THROUGH);

    assertThat(contribution.getOrder()).isEqualTo(Ordered.LOWEST_PRECEDENCE);
  }

  @Test
  void rejectsANullInterceptor() {
    assertThatThrownBy(() -> new OutboundInterceptorContribution(null, 0))
        .isInstanceOf(NullPointerException.class)
        .hasMessage("interceptor");
  }
}
