package org.opentmf.client.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class OpenTmfClientExchangeTimeoutExceptionTest {

  @Test
  void namesTheBound_andIsASocketTimeout() {
    var ex = new OpenTmfClientExchangeTimeoutException(Duration.ofSeconds(3), null);

    assertThat(ex.getExchangeTimeout()).isEqualTo(Duration.ofSeconds(3));
    assertThat(ex).isInstanceOf(SocketTimeoutException.class)
        .hasMessage("exchange-timeout of 3000 ms passed before the response was fully received")
        .hasNoCause();
  }

  @Test
  void carriesWhatTheCutDidToTheConnection() {
    var closed = new IOException("Socket closed");

    assertThat(new OpenTmfClientExchangeTimeoutException(Duration.ofMillis(1500), closed))
        .hasCause(closed);
  }
}
