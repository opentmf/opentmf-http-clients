package org.opentmf.client.starter;

import static org.assertj.core.api.Assertions.catchThrowable;

import ch.qos.logback.classic.Logger;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.opentmf.client.common.exception.OpenTmfClientResponseException;
import org.opentmf.client.reactive.service.api.TokenService;
import org.opentmf.client.rest.service.api.SyncTokenService;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * The compatibility harness of docs/token-single-flight-compatibility.md: it measures and asserts
 * nothing, and sets no bearer-auth.failure-memory: a client on defaults, wired by the starter.
 * Not part of the build; to run it, copy it into
 * opentmf-http-clients-autoconfigure/src/test/java/org/opentmf/client/starter/ of the checkout to
 * measure and run it with -Dit.test=CompatTokenCellsIT.
 */
class CompatTokenCellsIT {

  private static final Logger log = (Logger) LoggerFactory.getLogger(CompatTokenCellsIT.class);

  @Configuration(proxyBeanMethods = false)
  static class InfraConfig {
    @Bean
    WebClient.Builder webClientBuilder() {
      return WebClient.builder();
    }

    @Bean
    MeterRegistry meterRegistry() {
      return new SimpleMeterRegistry();
    }
  }

  private static ServerSocket server;
  private static ScheduledExecutorService answers;
  private static final AtomicInteger calls = new AtomicInteger();
  private static volatile String answer = "TOKEN";
  private static volatile long holdMillis;

  @BeforeAll
  static void start() throws IOException {
    server = new ServerSocket(0, 512);
    answers = Executors.newSingleThreadScheduledExecutor(r -> {
      var t = new Thread(r, "compat-answers");
      t.setDaemon(true);
      return t;
    });
    var acceptor = new Thread(() -> {
      while (!server.isClosed()) {
        try {
          var socket = server.accept();
          var t = new Thread(() -> serve(socket));
          t.setDaemon(true);
          t.start();
        } catch (IOException closed) {
          return;
        }
      }
    });
    acceptor.setDaemon(true);
    acceptor.start();
  }

  @AfterAll
  static void stop() throws IOException {
    server.close();
    answers.shutdownNow();
  }

  private static void serve(Socket socket) {
    try {
      InputStream in = socket.getInputStream();
      var head = new StringBuilder();
      int b;
      while ((b = in.read()) != -1) {
        head.append((char) b);
        if (head.length() >= 4 && head.lastIndexOf("\r\n\r\n") == head.length() - 4) {
          break;
        }
      }
      int length = 0;
      for (var line : head.toString().split("\r\n")) {
        if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
          length = Integer.parseInt(line.substring(15).trim());
        }
      }
      in.readNBytes(length);
    } catch (IOException gone) {
      return;
    }
    calls.incrementAndGet();
    var how = answer;
    answers.schedule(() -> write(socket, how), holdMillis, TimeUnit.MILLISECONDS);
  }

  private static void write(Socket socket, String how) {
    try (socket) {
      var text = switch (how) {
        case "TOKEN" -> response("200 OK", "{\"access_token\":\"tok\",\"expires_in\":300}");
        case "UNAVAILABLE" -> response("503 Service Unavailable", "");
        case "REFUSED" -> response("401 Unauthorized", "{\"error\":\"invalid_client\"}");
        case "EMPTY" -> response("200 OK", "");
        default -> null;
      };
      if (text != null) {
        socket.getOutputStream().write(text.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
      }
    } catch (IOException gone) {
      // closed by the client
    }
  }

  private static String response(String status, String body) {
    return "HTTP/1.1 " + status + "\r\nContent-Type: application/json\r\nContent-Length: "
        + body.length() + "\r\nConnection: close\r\n\r\n" + body;
  }

  /** One retrieval: the token, or the failure's type, its status and its cause chain. */
  private static String retrieve(AssertableApplicationContext context) {
    var failure = catchThrowable(() -> {
      if (context.containsBean("backendWebClient")) {
        context.getBean("backendTokenService", TokenService.class).getToken()
            .block(Duration.ofSeconds(60));
      } else {
        context.getBean("backendTokenService", SyncTokenService.class).getToken();
      }
    });
    if (failure == null) {
      return "token";
    }
    var line = new StringBuilder();
    for (var c = failure; c != null; c = c.getCause()) {
      line.append(line.isEmpty() ? "" : " <- ").append(c.getClass().getSimpleName());
      if (c instanceof OpenTmfClientResponseException e) {
        line.append('(').append(e.getStatusCode().value()).append(')');
      }
      if (c.getCause() == c) {
        break;
      }
    }
    return line.toString();
  }

  /** {@code callers} retrievals at the same moment; the outcomes, counted. */
  private static Map<String, Integer> together(AssertableApplicationContext context, int callers)
      throws InterruptedException {
    var outcomes = new ConcurrentHashMap<String, Integer>();
    var gate = new CountDownLatch(1);
    List<Thread> threads = new ArrayList<>();
    for (int i = 0; i < callers; i++) {
      var t = new Thread(() -> {
        try {
          gate.await();
          outcomes.merge(retrieve(context), 1, Integer::sum);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
      });
      t.start();
      threads.add(t);
    }
    gate.countDown();
    for (var t : threads) {
      t.join(120_000);
    }
    return new TreeMap<>(outcomes);
  }

  private static String meter(AssertableApplicationContext context) {
    var registry = context.getBean(MeterRegistry.class);
    var counts = new StringBuilder();
    for (var outcome : List.of("ok", "retried", "failed")) {
      var counter = registry.find("opentmf.client.token.fetch").tag("outcome", outcome).counter();
      counts.append(outcome).append('=')
          .append(counter == null ? 0 : (long) counter.count()).append(' ');
    }
    return counts.toString().trim();
  }

  @ParameterizedTest(name = "{0} {1} x{2} callers={3}")
  @CsvSource({
      "jdk, TOKEN, 1, 1", "jdk, TOKEN, 2, 1", "jdk, UNAVAILABLE, 1, 1", "jdk, UNAVAILABLE, 2, 1",
      "jdk, CLOSE, 1, 1", "jdk, REFUSED, 1, 1", "jdk, EMPTY, 1, 1",
      "jdk, TOKEN, 1, 8", "jdk, UNAVAILABLE, 1, 8",
      "apache, TOKEN, 1, 1", "apache, TOKEN, 2, 1", "apache, UNAVAILABLE, 1, 1",
      "apache, UNAVAILABLE, 2, 1", "apache, CLOSE, 1, 1", "apache, REFUSED, 1, 1",
      "apache, EMPTY, 1, 1", "apache, TOKEN, 1, 8", "apache, UNAVAILABLE, 1, 8",
      "netty, TOKEN, 1, 1", "netty, TOKEN, 2, 1", "netty, UNAVAILABLE, 1, 1",
      "netty, UNAVAILABLE, 2, 1", "netty, CLOSE, 1, 1", "netty, REFUSED, 1, 1",
      "netty, EMPTY, 1, 1", "netty, TOKEN, 1, 8", "netty, UNAVAILABLE, 1, 8"})
  void cell(String type, String how, int rounds, int callers) {
    answer = how;
    holdMillis = callers > 1 ? 300 : 0;
    calls.set(0);
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(OpentmfHttpClientsAutoConfiguration.class))
        .withUserConfiguration(InfraConfig.class)
        .withPropertyValues("opentmf.client-type=" + type,
            "opentmf.http-clients.backend.base-url=http://localhost:" + server.getLocalPort(),
            "opentmf.http-clients.backend.retry-wait-duration=200ms",
            "opentmf.http-clients.backend.bearer-auth.token-url=http://localhost:"
                + server.getLocalPort() + "/token",
            "opentmf.http-clients.backend.bearer-auth.client-id=client1",
            "opentmf.http-clients.backend.bearer-auth.client-secret=secret1",
            "opentmf.http-clients.backend.bearer-auth.form-data.grant_type=client_credentials")
        .run(context -> {
          long start = System.nanoTime();
          var outcomes = new ArrayList<Map<String, Integer>>();
          for (int r = 0; r < rounds; r++) {
            outcomes.add(together(context, callers));
          }
          long ms = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
          log.info("COMPAT|{}|{}|rounds={}|callers={}|calls={}|outcomes={}|meter={}|{} ms",
              type, how, rounds, callers, calls.get(), outcomes, meter(context), ms);
        });
  }
}
