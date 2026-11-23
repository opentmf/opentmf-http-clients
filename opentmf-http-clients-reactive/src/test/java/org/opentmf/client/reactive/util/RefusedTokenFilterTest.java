package org.opentmf.client.reactive.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class RefusedTokenFilterTest {

  private final List<String> refused = new ArrayList<>();

  @Test
  void aRefusalAsInvalid_isReported_andTheAnswerPassesThrough() {
    var filter = new RefusedTokenFilter();
    filter.bind(refused::add);
    var answer = answer(HttpStatus.UNAUTHORIZED, "Bearer error=\"invalid_token\"");

    assertThat(filter.filter(request("abc"), exchange(answer)).block()).isSameAs(answer);
    assertThat(refused).containsExactly("abc");
  }

  @Test
  void anotherAnswer_isNotReported() {
    var filter = new RefusedTokenFilter();
    filter.bind(refused::add);

    filter.filter(request("abc"),
        exchange(answer(HttpStatus.FORBIDDEN, "Bearer error=\"invalid_token\""))).block();
    filter.filter(request("abc"),
        exchange(answer(HttpStatus.UNAUTHORIZED, "Bearer realm=\"api\""))).block();

    assertThat(refused).isEmpty();
  }

  @Test
  void unbound_aRefusalIsIgnored() {
    var answer = answer(HttpStatus.UNAUTHORIZED, "Bearer error=\"invalid_token\"");

    assertThat(new RefusedTokenFilter().filter(request("abc"), exchange(answer)).block())
        .isSameAs(answer);
  }

  @Test
  void bind_findsTheFilterOfAWebClient() {
    var filter = new RefusedTokenFilter();
    var webClient = WebClient.builder().filter(filter).build();

    assertThat(RefusedTokenFilter.bind(webClient, refused::add)).isTrue();
    filter.filter(request("abc"),
        exchange(answer(HttpStatus.UNAUTHORIZED, "Bearer error=\"invalid_token\""))).block();

    assertThat(refused).containsExactly("abc");
    assertThat(RefusedTokenFilter.bind(WebClient.create(), refused::add)).isFalse();
  }

  private static ClientRequest request(String token) {
    return ClientRequest.create(HttpMethod.GET, URI.create("http://api/orders"))
        .headers(h -> h.setBearerAuth(token)).build();
  }

  private static ClientResponse answer(HttpStatus status, String challenge) {
    return ClientResponse.create(status).header(HttpHeaders.WWW_AUTHENTICATE, challenge).build();
  }

  private static ExchangeFunction exchange(ClientResponse answer) {
    return request -> Mono.just(answer);
  }
}
