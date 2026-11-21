package org.opentmf.client.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.opentmf.client.starter.SlowPeer.HEADER_DELAY_MS;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import io.netty.handler.timeout.ReadTimeoutException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.opentmf.client.bearer.exception.BearerTokenTransportException;
import org.opentmf.client.reactive.service.api.TokenService;
import org.opentmf.client.rest.service.api.SyncTokenService;
import org.opentmf.client.starter.SlowPeer.Behaviour;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.zalando.logbook.autoconfigure.LogbookAutoConfiguration;

/**
 * What {@code exchange-timeout} bounds, measured against a peer that holds back bytes: the whole
 * exchange, from the send to the last byte of the body. On apache and netty it cuts a body that
 * keeps arriving, which {@code response-timeout} (silence per read) never cuts; a stall is still
 * cut by {@code response-timeout} when that comes first. On jdk, where {@code response-timeout}
 * already is one deadline over the whole exchange, the smaller of the two applies.
 *
 * <p>A cut never passes as an answer: the caller sees the client's transport failure (the body
 * extraction error or the I/O error of the blocking clients, {@code WebClientRequestException} on
 * netty), never a status exception.</p>
 */
@ExtendWith(OutputCaptureExtension.class)
class ExchangeTimeoutIT {

  private static final Logger log = (Logger) LoggerFactory.getLogger(ExchangeTimeoutIT.class);

  private static final long RESPONSE_TIMEOUT_MS = 2000;
  /** A slack for scheduling on a loaded machine, on either side of an expected moment. */
  private static final long SLACK_MS = 800;

  @Configuration(proxyBeanMethods = false)
  static class ReactiveInfraConfig {

    @Bean
    WebClient.Builder webClientBuilder() {
      return WebClient.builder();
    }
  }

  private static SlowPeer peer;
  private static Level logbookLevel;

  @BeforeAll
  static void startPeer() throws IOException {
    peer = SlowPeer.start();
    logbookLevel = ((Logger) LoggerFactory.getLogger("org.zalando.logbook")).getLevel();
  }

  @AfterAll
  static void stopPeer() throws IOException {
    peer.close();
    ((Logger) LoggerFactory.getLogger("org.zalando.logbook")).setLevel(logbookLevel);
  }

  @AfterEach
  void closeConnections() {
    peer.closeConnections();
  }

  private static ApplicationContextRunner runner(String clientType, boolean logbook,
      long responseTimeoutMs, long exchangeTimeoutMs) {
    var configurations = logbook
        ? AutoConfigurations.of(LogbookAutoConfiguration.class,
            OpentmfHttpClientsAutoConfiguration.class)
        : AutoConfigurations.of(OpentmfHttpClientsAutoConfiguration.class);
    // Logbook reads the body inside the request only when it writes, i.e. at TRACE
    ((Logger) LoggerFactory.getLogger("org.zalando.logbook"))
        .setLevel(logbook ? Level.TRACE : logbookLevel);
    return new ApplicationContextRunner()
        .withConfiguration(configurations)
        .withUserConfiguration(ReactiveInfraConfig.class)
        .withPropertyValues(
            "opentmf.client-type=" + clientType,
            "opentmf.http-clients.backend.base-url=" + peer.baseUrl(),
            "opentmf.http-clients.backend.response-timeout=" + responseTimeoutMs + "ms",
            "opentmf.http-clients.backend.exchange-timeout=" + exchangeTimeoutMs + "ms",
            "opentmf.http-clients.backend.num-retries=0");
  }

  /** The body of one GET, through the client type's own client bean. */
  private static byte[] get(AssertableApplicationContext context) {
    if (context.containsBean("backendWebClient")) {
      return ((WebClient) context.getBean("backendWebClient")).get().uri("/download").retrieve()
          .bodyToMono(byte[].class).block(Duration.ofSeconds(30));
    }
    return ((RestClient) context.getBean("backendRestClient")).get().uri("/download").retrieve()
        .body(byte[].class);
  }

  private record Outcome(long millis, byte[] body, Throwable failure) {

    /** The outcome as the cell's log line reports it: time, body or exception chain. */
    String describe() {
      if (failure == null) {
        return body == null ? "completed after " + millis + " ms with no body"
            : "completed after " + millis + " ms with " + body.length + " bytes: "
                + new String(body, StandardCharsets.US_ASCII);
      }
      var chain = new StringBuilder("cut after " + millis + " ms:");
      for (var cause = failure; cause != null; cause = cause.getCause()) {
        chain.append(" <- ").append(cause.getClass().getName()).append(": ")
            .append(cause.getMessage());
        if (cause.getCause() == cause) {
          break;
        }
      }
      return chain.toString();
    }
  }

  private static Outcome measure(String cell, ThrowingSupplier<byte[]> call) {
    long start = System.nanoTime();
    var body = new byte[1][];
    var failure = catchThrowable(() -> body[0] = call.get());
    var outcome = new Outcome(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), body[0],
        failure);
    log.info("measured {}: {}", cell, outcome.describe());
    return outcome;
  }

  @FunctionalInterface
  private interface ThrowingSupplier<T> {
    T get() throws Exception;
  }

  private static void assertCutAround(Outcome outcome, long expectedMs) {
    assertThat(outcome.failure()).as("the call").isNotNull();
    assertThat(outcome.millis()).isBetween(expectedMs - SLACK_MS / 2, expectedMs + SLACK_MS);
  }

  /** The cut was made by the exchange timeout: its own message, on a socket-timeout type. */
  private static void assertExchangeTimeoutCause(Throwable cause, long exchangeTimeoutMs) {
    assertThat(cause).isExactlyInstanceOf(SocketTimeoutException.class)
        .hasMessage("exchange-timeout of " + exchangeTimeoutMs
            + " ms passed before the response was fully received");
  }

  /**
   * Never an answer: no status exception of either client. With the exact transport types and
   * the {@code SocketTimeoutException} cause asserted per backend in every cell, this pins the
   * shape a caller relies on against a later change.
   */
  private static void assertNoStatusException(Throwable failure) {
    assertThat(failure).isNotInstanceOf(RestClientResponseException.class)
        .isNotInstanceOf(WebClientResponseException.class);
  }

  /**
   * A body that keeps arriving, one byte every 700 ms over 4.9 s, which {@code response-timeout}
   * alone never cuts on apache and netty: cut at {@code exchange-timeout}. On jdk the smaller of
   * the two bounds cuts it.
   */
  @ParameterizedTest(name = "{0}, exchange-timeout {1} ms, logbook writing: {2}")
  @CsvSource({
      "jdk, 3000, false, 2000",
      "jdk, 1000, false, 1000",
      "apache, 3000, false, 3000",
      "apache, 3000, true, 3000",
      "apache, 1000, false, 1000",
      "netty, 3000, false, 3000",
      "netty, 3000, true, 3000",
      "netty, 1000, false, 1000"})
  void aBodyThatKeepsArriving_isCut_atTheExchangeTimeout(String clientType, long exchangeMs,
      boolean logbook, long expectedCutMs) {
    peer.behave(Behaviour.TRICKLE);

    runner(clientType, logbook, RESPONSE_TIMEOUT_MS, exchangeMs).run(context -> {
      var outcome = measure("trickle " + clientType + " exchange " + exchangeMs + " logbook "
          + logbook, () -> get(context));

      assertCutAround(outcome, expectedCutMs);
      var failure = outcome.failure();
      assertNoStatusException(failure);
      switch (clientType) {
        case "jdk" -> {
          // the JDK client's own deadline, as without exchange-timeout
          assertThat(failure).isExactlyInstanceOf(RestClientException.class);
          assertThat(failure.getCause()).isExactlyInstanceOf(IOException.class);
        }
        case "apache" -> {
          assertThat(failure).isExactlyInstanceOf(RestClientException.class);
          assertExchangeTimeoutCause(failure.getCause(), exchangeMs);
        }
        default -> {
          assertThat(failure).isExactlyInstanceOf(WebClientRequestException.class);
          assertExchangeTimeoutCause(failure.getCause(), exchangeMs);
        }
      }
    });
  }

  /**
   * A body that stalls after 8 of its 64 bytes is still cut by {@code response-timeout} when that
   * comes first (at 3.2 s on apache and netty, 2.0 s on jdk), not held to the later
   * {@code exchange-timeout}. On netty the cut is reported as a transport failure, not as the
   * {@code 200} it is reported as without {@code exchange-timeout}.
   */
  @ParameterizedTest(name = "{0}, logbook writing: {1}")
  @CsvSource({"jdk, false", "apache, false", "apache, true", "netty, false", "netty, true"})
  void aBodyThatStalls_isStillCut_byTheResponseTimeout_whenThatComesFirst(String clientType,
      boolean logbook) {
    peer.behave(Behaviour.STALL);
    long exchangeMs = 6000;

    runner(clientType, logbook, RESPONSE_TIMEOUT_MS, exchangeMs).run(context -> {
      var outcome = measure("stall " + clientType + " logbook " + logbook, () -> get(context));

      var failure = outcome.failure();
      assertNoStatusException(failure);
      switch (clientType) {
        case "jdk" -> {
          assertCutAround(outcome, RESPONSE_TIMEOUT_MS);
          assertThat(failure).isExactlyInstanceOf(RestClientException.class);
          assertThat(failure.getCause()).isExactlyInstanceOf(IOException.class);
        }
        case "apache" -> {
          assertCutAround(outcome, HEADER_DELAY_MS + RESPONSE_TIMEOUT_MS);
          assertThat(failure).isExactlyInstanceOf(RestClientException.class);
          assertThat(failure.getCause()).isInstanceOf(SocketTimeoutException.class)
              .hasMessage("Read timed out");
        }
        default -> {
          assertCutAround(outcome, HEADER_DELAY_MS + RESPONSE_TIMEOUT_MS);
          assertThat(failure).isExactlyInstanceOf(WebClientRequestException.class);
          assertThat(failure.getCause()).isInstanceOf(ReadTimeoutException.class);
        }
      }
    });
  }

  /**
   * Headers that would come after 4 s, inside a 5 s {@code response-timeout}: cut at a 3 s
   * {@code exchange-timeout}, before any answer, as the client's I/O failure.
   */
  @ParameterizedTest
  @ValueSource(strings = {"jdk", "apache", "netty"})
  void headersThatComeTooLate_areCut_atTheExchangeTimeout(String clientType) {
    peer.behave(Behaviour.LATE_HEADERS);
    long exchangeMs = 3000;

    runner(clientType, false, 5000, exchangeMs).run(context -> {
      var outcome = measure("late headers " + clientType, () -> get(context));

      assertCutAround(outcome, exchangeMs);
      var failure = outcome.failure();
      assertNoStatusException(failure);
      switch (clientType) {
        case "jdk" -> {
          assertThat(failure).isExactlyInstanceOf(ResourceAccessException.class);
          assertThat(failure.getCause()).isInstanceOf(HttpTimeoutException.class);
        }
        case "apache" -> {
          assertThat(failure).isExactlyInstanceOf(ResourceAccessException.class);
          assertExchangeTimeoutCause(failure.getCause(), exchangeMs);
        }
        default -> {
          assertThat(failure).isExactlyInstanceOf(WebClientRequestException.class);
          assertExchangeTimeoutCause(failure.getCause(), exchangeMs);
        }
      }
    });
  }

  /**
   * A body that arrives inside the bound is handed over unchanged with Logbook writing, and
   * Logbook logs it once.
   */
  @ParameterizedTest
  @ValueSource(strings = {"jdk", "apache", "netty"})
  void aCompleteBody_isUnchanged_andLoggedOnce(String clientType, CapturedOutput output) {
    peer.behave(Behaviour.COMPLETE);
    int from = output.getAll().length();

    runner(clientType, true, RESPONSE_TIMEOUT_MS, 3000).run(context -> {
      var outcome = measure("complete " + clientType, () -> get(context));

      assertThat(outcome.failure()).as("the call").isNull();
      assertThat(outcome.body()).isEqualTo("abcdefgh".getBytes(StandardCharsets.US_ASCII));
    });

    var responseLines = output.getAll().substring(from).lines()
        .filter(line -> line.contains("org.zalando.logbook"))
        .filter(line -> line.contains("\"type\":\"response\""))
        .toList();
    assertThat(responseLines).hasSize(1);
    assertThat(responseLines.get(0)).contains("abcdefgh").contains("\"status\":200");
  }

  /**
   * The same through the {@code RestTemplate} bean, which shares the {@code RestClient}'s request
   * factory and interceptors but not the client object itself.
   */
  @ParameterizedTest
  @ValueSource(strings = {"jdk", "apache"})
  void aCompleteBody_throughTheRestTemplate_isUnchanged_andLoggedOnce(String clientType,
      CapturedOutput output) {
    peer.behave(Behaviour.COMPLETE);
    int from = output.getAll().length();

    runner(clientType, true, RESPONSE_TIMEOUT_MS, 3000).run(context -> {
      var body = ((RestTemplate) context.getBean("backendRestTemplate"))
          .getForObject("/download", byte[].class);

      assertThat(body).isEqualTo("abcdefgh".getBytes(StandardCharsets.US_ASCII));
    });

    var responseLines = output.getAll().substring(from).lines()
        .filter(line -> line.contains("org.zalando.logbook"))
        .filter(line -> line.contains("\"type\":\"response\""))
        .toList();
    assertThat(responseLines).hasSize(1);
    assertThat(responseLines.get(0)).contains("abcdefgh").contains("\"status\":200");
  }

  /**
   * The token call runs under the client's {@code exchange-timeout} too: a token answer that keeps
   * trickling is cut at it, retried once at once as any transport failure of a mint, and then
   * reported as {@code BearerTokenTransportException}.
   */
  @ParameterizedTest
  @ValueSource(strings = {"jdk", "apache", "netty"})
  void theMint_isCut_atTheClientsExchangeTimeout(String clientType) {
    peer.behave(Behaviour.TRICKLE);
    long exchangeMs = 1500;

    runner(clientType, false, RESPONSE_TIMEOUT_MS, exchangeMs)
        .withPropertyValues(
            "opentmf.http-clients.backend.bearer-auth.token-url=" + peer.baseUrl() + "/token",
            "opentmf.http-clients.backend.bearer-auth.client-id=client",
            "opentmf.http-clients.backend.bearer-auth.client-secret=secret",
            "opentmf.http-clients.backend.bearer-auth.form-data.grant_type=client_credentials")
        .run(context -> {
          var tokenService = context.getBean("backendTokenService");
          var outcome = measure("mint " + clientType, () -> {
            var token = tokenService instanceof TokenService reactive
                ? reactive.getToken().block(Duration.ofSeconds(30))
                : ((SyncTokenService) tokenService).getToken();
            return token.getBytes(StandardCharsets.US_ASCII);
          });

          // two attempts, each cut at the exchange timeout
          assertCutAround(outcome, 2 * exchangeMs);
          assertThat(outcome.failure()).isInstanceOf(BearerTokenTransportException.class);
          var cause = outcome.failure().getCause();
          if (clientType.equals("jdk")) {
            assertThat(cause).isExactlyInstanceOf(RestClientException.class);
            assertThat(cause.getCause()).isExactlyInstanceOf(IOException.class);
          } else {
            assertExchangeTimeoutCause(cause.getCause(), exchangeMs);
          }
        });
  }
}
