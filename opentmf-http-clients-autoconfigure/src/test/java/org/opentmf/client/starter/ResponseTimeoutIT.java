package org.opentmf.client.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import io.netty.handler.timeout.ReadTimeoutException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.zalando.logbook.autoconfigure.LogbookAutoConfiguration;

/**
 * What {@code response-timeout} bounds on each client type, measured against a peer that answers
 * 200 with its headers and then either stalls in the body or trickles it in.
 *
 * <ul>
 *   <li>{@code jdk}: one deadline over the whole exchange, counted from the send (Spring's
 *       {@code JdkClientHttpRequest} closes the body stream when it passes). A stalled body is
 *       cut, and so is a body that is still arriving.</li>
 *   <li>{@code apache} and {@code netty}: silence per read. A stalled body is cut after
 *       {@code response-timeout} without a byte; a body that keeps arriving is never cut.</li>
 * </ul>
 *
 * <p>The peer is a plain socket, so a byte can be held back: the stalled peer answers its headers
 * after {@value #HEADER_DELAY_MS} ms, sends part of its body and goes silent, which separates
 * "from the send" (cut at ~{@value #TIMEOUT_MS} ms) from "since the last byte" (cut at
 * ~{@value #HEADER_DELAY_MS} + {@value #TIMEOUT_MS} ms). The trickling peer sends one byte every
 * {@value #TRICKLE_GAP_MS} ms, each gap well inside the timeout, the whole body well beyond
 * it.</p>
 */
class ResponseTimeoutIT {

  private static final long TIMEOUT_MS = 2000;
  private static final long HEADER_DELAY_MS = 1200;
  private static final long TRICKLE_GAP_MS = 700;
  private static final int TRICKLE_BYTES = 8;
  /** A slack for scheduling on a loaded machine, on either side of an expected moment. */
  private static final long SLACK_MS = 800;

  @Configuration(proxyBeanMethods = false)
  static class ReactiveInfraConfig {

    @Bean
    WebClient.Builder webClientBuilder() {
      return WebClient.builder();
    }
  }

  private enum Behaviour { STALL, TRICKLE }

  private static ServerSocket server;
  private static volatile Behaviour behaviour = Behaviour.STALL;
  private static final List<Socket> accepted = new CopyOnWriteArrayList<>();
  private static Level logbookLevel;

  @BeforeAll
  static void startPeer() throws IOException {
    server = new ServerSocket(0);
    var acceptor = new Thread(ResponseTimeoutIT::acceptLoop, "slow-peer");
    acceptor.setDaemon(true);
    acceptor.start();
    var logbook = (Logger) LoggerFactory.getLogger("org.zalando.logbook");
    logbookLevel = logbook.getLevel();
  }

  @AfterAll
  static void stopPeer() throws IOException {
    server.close();
    ((Logger) LoggerFactory.getLogger("org.zalando.logbook")).setLevel(logbookLevel);
  }

  @AfterEach
  void closeConnections() {
    for (var socket : accepted) {
      try {
        socket.close();
      } catch (IOException ignored) {
        // already gone
      }
    }
    accepted.clear();
  }

  private static void acceptLoop() {
    while (!server.isClosed()) {
      try {
        var socket = server.accept();
        accepted.add(socket);
        var answer = new Thread(() -> answer(socket, behaviour), "slow-peer-answer");
        answer.setDaemon(true);
        answer.start();
      } catch (IOException closed) {
        return;
      }
    }
  }

  private static void answer(Socket socket, Behaviour how) {
    try (socket) {
      readRequestHead(socket.getInputStream());
      var out = socket.getOutputStream();
      if (how == Behaviour.STALL) {
        TimeUnit.MILLISECONDS.sleep(HEADER_DELAY_MS);
        head(out, 64);
        out.write("partial!".getBytes(StandardCharsets.US_ASCII));
        out.flush();
        // the other 56 bytes never come; the socket stays open until the cell closes it
        TimeUnit.SECONDS.sleep(30);
      } else {
        head(out, TRICKLE_BYTES);
        for (int i = 0; i < TRICKLE_BYTES; i++) {
          if (i > 0) {
            TimeUnit.MILLISECONDS.sleep(TRICKLE_GAP_MS);
          }
          out.write('a' + i);
          out.flush();
        }
        TimeUnit.SECONDS.sleep(30);
      }
    } catch (IOException gone) {
      // the client closed the connection: expected once it gives up
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private static void readRequestHead(InputStream in) throws IOException {
    int matched = 0;
    byte[] end = {'\r', '\n', '\r', '\n'};
    int b;
    while (matched < end.length && (b = in.read()) != -1) {
      matched = b == end[matched] ? matched + 1 : (b == '\r' ? 1 : 0);
    }
  }

  private static void head(OutputStream out, int contentLength) throws IOException {
    out.write(("HTTP/1.1 200 OK\r\n"
        + "Content-Type: text/plain\r\n"
        + "Content-Length: " + contentLength + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
    out.flush();
  }

  private static ApplicationContextRunner runner(boolean logbook) {
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
            "opentmf.http-clients.backend.base-url=http://localhost:" + server.getLocalPort(),
            "opentmf.http-clients.backend.response-timeout=" + TIMEOUT_MS + "ms",
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
  }

  private static Outcome call(AssertableApplicationContext context) {
    long start = System.nanoTime();
    var body = new byte[1][];
    var failure = catchThrowable(() -> body[0] = get(context));
    return new Outcome(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), body[0],
        failure);
  }

  private static Throwable rootCause(Throwable failure) {
    var cause = failure;
    while (cause.getCause() != null && cause.getCause() != cause) {
      cause = cause.getCause();
    }
    return cause;
  }

  @ParameterizedTest(name = "{0}, logbook writing: {1}")
  @CsvSource({"jdk, false", "jdk, true", "apache, false", "apache, true", "netty, false",
      "netty, true"})
  void aBodyThatStallsAfterItsHeaders_isCut(String clientType, boolean logbook) {
    behaviour = Behaviour.STALL;

    runner(logbook).withPropertyValues("opentmf.client-type=" + clientType).run(context -> {
      var outcome = call(context);
      assertThat(outcome.failure()).as("the call").isNotNull();

      if (clientType.equals("jdk")) {
        // one deadline from the send: the header delay counts against it
        assertThat(outcome.millis()).isBetween(TIMEOUT_MS - SLACK_MS, TIMEOUT_MS + SLACK_MS);
      } else {
        // silence per read: the clock starts again with the last byte
        assertThat(outcome.millis()).isBetween(HEADER_DELAY_MS + TIMEOUT_MS - SLACK_MS / 2,
            HEADER_DELAY_MS + TIMEOUT_MS + SLACK_MS * 2);
      }
      assertFailureShape(clientType, logbook, outcome.failure());
    });
  }

  /**
   * The exception a caller sees when the body is cut. On the blocking clients it depends on who
   * reads the body: Logbook, when it writes, reads it inside the exchange (an I/O error of the
   * request); otherwise the caller's {@code body(..)} does (an error of extracting the body).
   * WebClient reports a failed body read on a 200 as a {@code WebClientResponseException} of
   * status 200 caused by the read failure.
   */
  private static void assertFailureShape(String clientType, boolean logbook, Throwable failure) {
    switch (clientType) {
      case "jdk" -> {
        assertThat(failure).isExactlyInstanceOf(
            logbook ? ResourceAccessException.class : RestClientException.class);
        assertThat(rootCause(failure)).isExactlyInstanceOf(IOException.class)
            .hasMessage("closed");
      }
      case "apache" -> {
        assertThat(failure).isExactlyInstanceOf(
            logbook ? ResourceAccessException.class : RestClientException.class);
        assertThat(rootCause(failure)).isInstanceOf(SocketTimeoutException.class);
      }
      default -> {
        assertThat(failure).isInstanceOf(WebClientResponseException.class);
        assertThat(((WebClientResponseException) failure).getStatusCode().value())
            .isEqualTo(200);
        assertThat(rootCause(failure)).isInstanceOf(ReadTimeoutException.class);
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"jdk", "apache", "netty"})
  void aBodyThatKeepsArriving_isCutOnJdkOnly(String clientType) {
    behaviour = Behaviour.TRICKLE;
    long wholeBody = (TRICKLE_BYTES - 1) * TRICKLE_GAP_MS;

    runner(false).withPropertyValues("opentmf.client-type=" + clientType).run(context -> {
      var outcome = call(context);

      if (clientType.equals("jdk")) {
        assertThat(outcome.failure()).as("the call").isNotNull();
        assertThat(outcome.millis()).isBetween(TIMEOUT_MS - SLACK_MS, TIMEOUT_MS + SLACK_MS);
        assertFailureShape(clientType, false, outcome.failure());
      } else {
        assertThat(outcome.failure()).as("the call").isNull();
        assertThat(new String(outcome.body(), StandardCharsets.US_ASCII)).isEqualTo("abcdefgh");
        assertThat(outcome.millis()).isGreaterThanOrEqualTo(wholeBody)
            .isGreaterThan(TIMEOUT_MS * 2);
      }
    });
  }

  /** On netty a caller bounds the whole call with the Mono's own timeout. */
  @Test
  void aReactiveCaller_boundsTheWholeCall_withTheMonosTimeout() {
    behaviour = Behaviour.TRICKLE;

    runner(false).withPropertyValues("opentmf.client-type=netty").run(context -> {
      long start = System.nanoTime();
      var failure = catchThrowable(() -> ((WebClient) context.getBean("backendWebClient")).get()
          .uri("/download").retrieve().bodyToMono(byte[].class)
          .timeout(Duration.ofMillis(TIMEOUT_MS)).block(Duration.ofSeconds(30)));
      long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

      assertThat(failure).isNotNull();
      assertThat(rootCause(failure)).isInstanceOf(TimeoutException.class);
      assertThat(millis).isBetween(TIMEOUT_MS - SLACK_MS, TIMEOUT_MS + SLACK_MS);
    });
  }
}
