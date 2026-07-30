package org.opentmf.client.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class OpenTmfClientResilienceExceptionTest {

  @Test
  void carriesClientIdMessageAndCause() {
    var cause = new IllegalStateException("circuit open");
    var ex = new OpenTmfClientResilienceException("onedms", "call rejected", cause);

    assertThat(ex.getClientId()).isEqualTo("onedms");
    assertThat(ex.getMessage()).isEqualTo("call rejected");
    assertThat(ex.getCause()).isSameAs(cause);
    assertThat(ex)
        .isInstanceOf(RuntimeException.class)
        .isNotInstanceOf(OpenTmfClientResponseException.class);
  }
}
