package org.opentmf.client.reactive.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.opentmf.client.common.exception.OpenTmfClientExchangeTimeoutException;
import org.opentmf.client.common.model.ClientProperties;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * The whole-exchange bound of the reactive client: the headers and every buffer of the body
 * inside it, a cut reported as the client's transport failure.
 */
class ExchangeTimeoutFilterTest {

  private static final Duration BOUND = Duration.ofMillis(300);
  private static final ClientRequest REQUEST =
      ClientRequest.create(HttpMethod.GET, URI.create("http://peer/download")).build();

  private static DataBuffer buffer(String text) {
    return DefaultDataBufferFactory.sharedInstance.wrap(text.getBytes(StandardCharsets.US_ASCII));
  }

  private static Mono<String> bodyThrough(Flux<DataBuffer> body) {
    ExchangeFunction peer = request -> Mono.just(ClientResponse.create(HttpStatus.OK)
        .body(body).build());
    return new ExchangeTimeoutFilter(BOUND).filter(REQUEST, peer)
        .flatMap(response -> response.bodyToMono(String.class));
  }

  private static void assertExchangeTimeout(Throwable failure) {
    assertThat(failure).isExactlyInstanceOf(WebClientRequestException.class);
    assertThat(((WebClientRequestException) failure).getMethod()).isEqualTo(HttpMethod.GET);
    assertThat(((WebClientRequestException) failure).getUri()).isEqualTo(REQUEST.url());
    assertThat(failure.getCause()).isInstanceOf(OpenTmfClientExchangeTimeoutException.class)
        .hasMessage("exchange-timeout of 300 ms passed before the response was fully received");
  }

  @Test
  void headersThatNeverCome_areCut_atTheBound() {
    ExchangeFunction silent = request -> Mono.never();

    StepVerifier.create(new ExchangeTimeoutFilter(BOUND).filter(REQUEST, silent))
        .expectErrorSatisfies(ExchangeTimeoutFilterTest::assertExchangeTimeout)
        .verify(Duration.ofSeconds(10));
  }

  @Test
  void aBodyThatKeepsArriving_isCut_atTheBound() {
    var trickle = Flux.interval(Duration.ofMillis(100)).map(i -> buffer("x"));

    StepVerifier.create(bodyThrough(trickle))
        .expectErrorSatisfies(ExchangeTimeoutFilterTest::assertExchangeTimeout)
        .verify(Duration.ofSeconds(10));
  }

  @Test
  void aBodyInsideTheBound_passesUnchanged() {
    StepVerifier.create(bodyThrough(Flux.just(buffer("abcd"), buffer("efgh"))))
        .expectNext("abcdefgh")
        .verifyComplete();
  }

  @Test
  void aBodyThatFailsOnTheConnection_failsAsATransportFailure_notAsTheAnswer() {
    var reset = new IOException("Connection reset");

    StepVerifier.create(bodyThrough(Flux.concat(Flux.just(buffer("ab")), Flux.error(reset))))
        .expectErrorSatisfies(failure -> {
          assertThat(failure).isExactlyInstanceOf(WebClientRequestException.class);
          assertThat(failure.getCause()).isSameAs(reset);
        })
        .verify(Duration.ofSeconds(10));
  }

  @Test
  void aWebClientFailureOfTheBody_passesAsItIs() {
    var own = new WebClientRequestException(new IOException("reported by WebClient"),
        HttpMethod.GET, REQUEST.url(), new HttpHeaders());

    StepVerifier.create(bodyThrough(Flux.error(own)))
        .expectErrorSatisfies(failure -> assertThat(failure).isSameAs(own))
        .verify(Duration.ofSeconds(10));
  }

  private static List<ExchangeFilterFunction> filtersOf(ClientProperties properties)
      throws Exception {
    var webClient = WebClientConfigUtil.createWebClient(WebClient.builder(),
        WebClientConfigUtil.httpClient(null, "test", properties), properties);
    var filters = new ArrayList<ExchangeFilterFunction>();
    webClient.mutate().filters(filters::addAll);
    return filters;
  }

  @Test
  void theWebClient_carriesTheBound_onlyWhenItIsSet() throws Exception {
    var properties = new ClientProperties();
    assertThat(filtersOf(properties)).noneMatch(ExchangeTimeoutFilter.class::isInstance);

    properties.setExchangeTimeout(Duration.ofSeconds(3));
    var filters = filtersOf(properties);
    assertThat(filters.get(filters.size() - 1)).isInstanceOf(ExchangeTimeoutFilter.class);
  }
}
