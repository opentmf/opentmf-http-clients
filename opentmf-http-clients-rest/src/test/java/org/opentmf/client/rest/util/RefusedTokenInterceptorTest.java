package org.opentmf.client.rest.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;

class RefusedTokenInterceptorTest {

  private final List<String> refused = new ArrayList<>();

  @Test
  void aRefusalAsInvalid_isReported_andTheAnswerReturnedUntouched() throws IOException {
    var interceptor = new RefusedTokenInterceptor();
    interceptor.bind(refused::add);
    var answer = answer(HttpStatus.UNAUTHORIZED, "Bearer error=\"invalid_token\"");

    var returned = interceptor.intercept(request("abc"), new byte[0], (r, b) -> answer);

    assertThat(returned).isSameAs(answer);
    assertThat(refused).containsExactly("abc");
  }

  @Test
  void anotherAnswer_isNotReported() throws IOException {
    var interceptor = new RefusedTokenInterceptor();
    interceptor.bind(refused::add);

    interceptor.intercept(request("abc"), new byte[0],
        (r, b) -> answer(HttpStatus.FORBIDDEN, "Bearer error=\"invalid_token\""));
    interceptor.intercept(request("abc"), new byte[0],
        (r, b) -> answer(HttpStatus.UNAUTHORIZED, "Bearer realm=\"api\""));

    assertThat(refused).isEmpty();
  }

  @Test
  void unbound_aRefusalIsIgnored() throws IOException {
    var answer = answer(HttpStatus.UNAUTHORIZED, "Bearer error=\"invalid_token\"");
    ClientHttpRequestExecution execution = (r, b) -> answer;

    assertThat(new RefusedTokenInterceptor().intercept(request("abc"), new byte[0], execution))
        .isSameAs(answer);
  }

  @Test
  void bind_findsTheInterceptorOfARestClient() throws IOException {
    var interceptor = new RefusedTokenInterceptor();
    var restClient = RestClient.builder().requestInterceptor(interceptor).build();

    assertThat(RefusedTokenInterceptor.bind(restClient, refused::add)).isTrue();
    interceptor.intercept(request("abc"), new byte[0],
        (r, b) -> answer(HttpStatus.UNAUTHORIZED, "Bearer error=\"invalid_token\""));

    assertThat(refused).containsExactly("abc");
    assertThat(RefusedTokenInterceptor.bind(RestClient.create(), refused::add)).isFalse();
  }

  private static HttpRequest request(String token) {
    var request = mock(HttpRequest.class);
    var headers = new HttpHeaders();
    headers.setBearerAuth(token);
    when(request.getHeaders()).thenReturn(headers);
    when(request.getMethod()).thenReturn(HttpMethod.GET);
    when(request.getURI()).thenReturn(URI.create("http://api/orders"));
    return request;
  }

  private static ClientHttpResponse answer(HttpStatus status, String challenge) {
    var response = mock(ClientHttpResponse.class);
    var headers = new HttpHeaders();
    headers.add(HttpHeaders.WWW_AUTHENTICATE, challenge);
    try {
      when(response.getStatusCode()).thenReturn(status);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
    when(response.getHeaders()).thenReturn(headers);
    return response;
  }
}
