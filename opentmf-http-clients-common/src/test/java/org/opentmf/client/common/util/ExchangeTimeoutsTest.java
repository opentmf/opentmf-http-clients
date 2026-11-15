package org.opentmf.client.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ExchangeTimeoutsTest {

  @Test
  void aSocketTimeout_namingTheBound() {
    assertThat(ExchangeTimeouts.exceeded(Duration.ofSeconds(3), null))
        .isExactlyInstanceOf(SocketTimeoutException.class)
        .hasMessage("exchange-timeout of 3000 ms passed before the response was fully received")
        .hasNoCause();
  }

  @Test
  void carriesWhatTheCutDidToTheConnection() {
    var closed = new IOException("Socket closed");

    assertThat(ExchangeTimeouts.exceeded(Duration.ofMillis(1500), closed)).hasCause(closed);
  }
}
