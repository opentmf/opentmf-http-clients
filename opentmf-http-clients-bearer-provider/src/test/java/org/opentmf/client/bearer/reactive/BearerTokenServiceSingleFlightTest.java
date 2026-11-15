package org.opentmf.client.bearer.reactive;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.benmanes.caffeine.cache.Caffeine;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opentmf.client.bearer.model.TokenEntry;
import org.opentmf.client.common.model.BearerAuthConfig;
import org.springframework.util.MultiValueMap;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;
import reactor.util.context.Context;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The reactive token service's one mint per key, against a token client that answers when told:
 * the mint runs in the context of the caller that started it, and a caller that cancels stops
 * waiting without cancelling the mint.
 */
class BearerTokenServiceSingleFlightTest {

  private static final String TRACE = "trace";

  private HeldTokenClient tokenClient;
  private BearerTokenServiceImpl service;

  @BeforeEach
  void setUp() {
    var config = new BearerAuthConfig();
    config.setTokenUrl(URI.create("http://idp/token"));
    config.setFormData(Map.of("grant_type", "client_credentials"));
    tokenClient = new HeldTokenClient();
    service = new BearerTokenServiceImpl(config, Caffeine.newBuilder().<String, TokenEntry>build(),
        tokenClient);
  }

  @Test
  void theMint_runsInTheContextOfTheCallerThatStartedIt() {
    var first = service.getToken().contextWrite(Context.of(TRACE, "first")).toFuture();
    var second = service.getToken().contextWrite(Context.of(TRACE, "second")).toFuture();

    tokenClient.answer();

    assertThat(first).succeedsWithin(Duration.ofSeconds(5)).isEqualTo("tok");
    assertThat(second).succeedsWithin(Duration.ofSeconds(5)).isEqualTo("tok");
    assertThat(tokenClient.calls).hasValue(1);
    assertThat(tokenClient.traces).containsExactly("first");
  }

  @Test
  void aCallerThatCancels_stopsWaiting_theMintFinishesAndIsCached() {
    var first = service.getToken().toFuture();
    var second = service.getToken().toFuture();

    first.cancel(true);
    tokenClient.answer();

    assertThat(second).succeedsWithin(Duration.ofSeconds(5)).isEqualTo("tok");
    StepVerifier.create(service.getToken()).expectNext("tok").verifyComplete();
    assertThat(tokenClient.calls).as("the third caller is served from the cache").hasValue(1);
  }

  @Test
  void aFailedMint_failsEveryCaller_andTheNextCallerMintsAgain() {
    var first = service.getToken().toFuture();
    var second = service.getToken().toFuture();

    tokenClient.fail(new IllegalStateException("down"));

    assertThat(first).failsWithin(Duration.ofSeconds(5));
    assertThat(second).failsWithin(Duration.ofSeconds(5));
    var third = service.getToken().toFuture();
    tokenClient.answer();
    assertThat(third).succeedsWithin(Duration.ofSeconds(5)).isEqualTo("tok");
    assertThat(tokenClient.calls).hasValue(2);
  }

  /** A token client whose answer is held until the test releases it. */
  private static final class HeldTokenClient implements BearerTokenClient {

    private final AtomicInteger calls = new AtomicInteger();
    private final List<String> traces = new CopyOnWriteArrayList<>();
    private volatile Sinks.One<ObjectNode> pending = Sinks.one();

    @Override
    public Mono<ObjectNode> retrieveToken(URI tokenUrl, MultiValueMap<String, String> formData) {
      return Mono.deferContextual(context -> {
        calls.incrementAndGet();
        traces.add(context.getOrDefault(TRACE, "none"));
        return pending.asMono();
      });
    }

    void answer() {
      var answering = pending;
      pending = Sinks.one();
      answering.tryEmitValue(JsonMapper.builder().build().createObjectNode()
          .put("access_token", "tok").put("expires_in", 300));
    }

    void fail(Throwable failure) {
      var failing = pending;
      pending = Sinks.one();
      failing.tryEmitError(failure);
    }
  }
}
