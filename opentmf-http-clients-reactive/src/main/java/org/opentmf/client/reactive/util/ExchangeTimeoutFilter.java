package org.opentmf.client.reactive.util;

import java.time.Duration;
import org.opentmf.client.common.util.ExchangeTimeouts;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClientException;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Bounds the whole exchange, from the send to the last byte of the body, by
 * {@code exchange-timeout}. Reactor Netty's own timeouts are silence per read, so a body that
 * keeps arriving is never cut by them.
 *
 * <p>When the bound passes the exchange is cancelled, which closes its connection, and the
 * caller gets a {@link WebClientRequestException} caused by the bound's
 * {@code SocketTimeoutException} ({@link ExchangeTimeouts}). A body that fails for another transport reason
 * (a read timeout, a premature close) fails as a {@code WebClientRequestException} too: without
 * this filter WebClient reports such a failure as a {@code WebClientResponseException} carrying
 * the answer's own status, a {@code 200} for a body cut short.</p>
 */
final class ExchangeTimeoutFilter implements ExchangeFilterFunction {

  private final Duration exchangeTimeout;

  ExchangeTimeoutFilter(Duration exchangeTimeout) {
    this.exchangeTimeout = exchangeTimeout;
  }

  @Override
  public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
    return Mono.defer(() -> {
      long deadline = System.nanoTime() + exchangeTimeout.toNanos();
      return next.exchange(request)
          .timeout(exchangeTimeout, Mono.error(() -> exceeded(request)))
          .map(response -> response.mutate()
              .body(body -> bounded(body, request, deadline))
              .build());
    });
  }

  private Flux<DataBuffer> bounded(Flux<DataBuffer> body, ClientRequest request, long deadline) {
    return body
        .onErrorMap(failure -> !(failure instanceof WebClientException),
            failure -> transportFailure(request, failure))
        .timeout(Mono.delay(remaining(deadline)), buffer -> Mono.delay(remaining(deadline)),
            Flux.error(() -> exceeded(request)));
  }

  private static Duration remaining(long deadline) {
    return Duration.ofNanos(Math.max(0, deadline - System.nanoTime()));
  }

  private WebClientRequestException exceeded(ClientRequest request) {
    return transportFailure(request, ExchangeTimeouts.exceeded(exchangeTimeout, null));
  }

  private static WebClientRequestException transportFailure(ClientRequest request,
      Throwable failure) {
    return new WebClientRequestException(failure, request.method(), request.url(),
        request.headers());
  }
}
