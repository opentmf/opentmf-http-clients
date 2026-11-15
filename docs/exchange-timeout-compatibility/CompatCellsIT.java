package org.opentmf.client.starter;

import static org.assertj.core.api.Assertions.catchThrowable;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;
import org.zalando.logbook.autoconfigure.LogbookAutoConfiguration;

/**
 * The compatibility harness of docs/exchange-timeout-compatibility.md: it measures and asserts
 * nothing, and sets no exchange-timeout. Not part of the build; to run it, copy it into
 * opentmf-http-clients-autoconfigure/src/test/java/org/opentmf/client/starter/ of the checkout
 * to measure and run it with -Dit.test=CompatCellsIT.
 */
class CompatCellsIT {

  private static final Logger log = (Logger) LoggerFactory.getLogger(CompatCellsIT.class);

  @Configuration(proxyBeanMethods = false)
  static class ReactiveInfraConfig {
    @Bean
    WebClient.Builder webClientBuilder() {
      return WebClient.builder();
    }
  }

  private static ServerSocket server;
  private static volatile String behaviour = "COMPLETE";
  private static final List<Socket> accepted = new CopyOnWriteArrayList<>();
  private static ScheduledExecutorService writes;
  private static Level logbookLevel;

  @BeforeAll
  static void start() throws IOException {
    server = new ServerSocket(0);
    writes = Executors.newSingleThreadScheduledExecutor(r -> {
      var t = new Thread(r, "compat-writes");
      t.setDaemon(true);
      return t;
    });
    var acceptor = new Thread(() -> {
      while (!server.isClosed()) {
        try {
          var socket = server.accept();
          accepted.add(socket);
          var how = behaviour;
          var t = new Thread(() -> answer(socket, how));
          t.setDaemon(true);
          t.start();
        } catch (IOException closed) {
          return;
        }
      }
    });
    acceptor.setDaemon(true);
    acceptor.start();
    logbookLevel = ((Logger) LoggerFactory.getLogger("org.zalando.logbook")).getLevel();
  }

  @AfterAll
  static void stop() throws IOException {
    server.close();
    writes.shutdownNow();
    ((Logger) LoggerFactory.getLogger("org.zalando.logbook")).setLevel(logbookLevel);
  }

  @AfterEach
  void closeConnections() {
    for (var s : accepted) {
      try {
        s.close();
      } catch (IOException ignored) {
        // gone
      }
    }
    accepted.clear();
  }

  private static void answer(Socket socket, String how) {
    try {
      InputStream in = socket.getInputStream();
      int matched = 0;
      byte[] end = {'\r', '\n', '\r', '\n'};
      int b;
      while (matched < end.length && (b = in.read()) != -1) {
        matched = b == end[matched] ? matched + 1 : (b == '\r' ? 1 : 0);
      }
    } catch (IOException gone) {
      return;
    }
    switch (how) {
      case "COMPLETE" -> send(socket, 0, head(200, 8) + "abcdefgh");
      case "STATUS_500" -> send(socket, 0, head(500, 8) + "brokenxx");
      case "LATE_HEADERS" -> send(socket, 4000, head(200, 8) + "abcdefgh");
      case "STALL" -> send(socket, 1200, head(200, 64) + "partial!");
      default -> {
        send(socket, 0, head(200, 8));
        for (int i = 0; i < 8; i++) {
          send(socket, i * 700L, String.valueOf((char) ('a' + i)));
        }
      }
    }
  }

  private static void send(Socket socket, long after, String text) {
    writes.schedule(() -> {
      try {
        socket.getOutputStream().write(text.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
      } catch (IOException gone) {
        // closed by the client
      }
    }, after, TimeUnit.MILLISECONDS);
  }

  private static String head(int status, int length) {
    return "HTTP/1.1 " + status + (status == 200 ? " OK" : " Internal Server Error") + "\r\n"
        + "Content-Type: text/plain\r\nContent-Length: " + length + "\r\n\r\n";
  }

  private static byte[] get(AssertableApplicationContext context) {
    if (context.containsBean("backendWebClient")) {
      return ((WebClient) context.getBean("backendWebClient")).get().uri("/download").retrieve()
          .bodyToMono(byte[].class).block(Duration.ofSeconds(30));
    }
    return ((RestClient) context.getBean("backendRestClient")).get().uri("/download").retrieve()
        .body(byte[].class);
  }

  @ParameterizedTest(name = "{0} {1} logbook {2}")
  @CsvSource({
      "jdk, COMPLETE, false", "jdk, COMPLETE, true", "jdk, STATUS_500, false",
      "jdk, STALL, false", "jdk, STALL, true", "jdk, TRICKLE, false", "jdk, TRICKLE, true",
      "jdk, LATE_HEADERS, false",
      "apache, COMPLETE, false", "apache, COMPLETE, true", "apache, STATUS_500, false",
      "apache, STALL, false", "apache, STALL, true", "apache, TRICKLE, false",
      "apache, TRICKLE, true", "apache, LATE_HEADERS, false",
      "netty, COMPLETE, false", "netty, COMPLETE, true", "netty, STATUS_500, false",
      "netty, STALL, false", "netty, STALL, true", "netty, TRICKLE, false",
      "netty, TRICKLE, true", "netty, LATE_HEADERS, false"})
  void cell(String type, String how, boolean logbook) {
    behaviour = how;
    var configurations = logbook
        ? AutoConfigurations.of(LogbookAutoConfiguration.class,
            OpentmfHttpClientsAutoConfiguration.class)
        : AutoConfigurations.of(OpentmfHttpClientsAutoConfiguration.class);
    ((Logger) LoggerFactory.getLogger("org.zalando.logbook"))
        .setLevel(logbook ? Level.TRACE : logbookLevel);
    new ApplicationContextRunner().withConfiguration(configurations)
        .withUserConfiguration(ReactiveInfraConfig.class)
        .withPropertyValues("opentmf.client-type=" + type,
            "opentmf.http-clients.backend.base-url=http://localhost:" + server.getLocalPort(),
            "opentmf.http-clients.backend.response-timeout=2000ms",
            "opentmf.http-clients.backend.num-retries=0")
        .run(context -> {
          long start = System.nanoTime();
          var body = new byte[1][];
          var failure = catchThrowable(() -> body[0] = get(context));
          long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
          var line = new StringBuilder();
          if (failure == null) {
            line.append("completed ").append(ms).append(" ms body=")
                .append(body[0] == null ? "null" : new String(body[0], StandardCharsets.US_ASCII));
          } else {
            line.append("failed ").append(ms).append(" ms");
            for (var c = failure; c != null; c = c.getCause()) {
              line.append(" <- ").append(c.getClass().getName());
              if (c.getCause() == c) {
                break;
              }
            }
          }
          log.info("COMPAT|{}|{}|logbook={}|{}", type, how, logbook, line);
        });
  }
}
