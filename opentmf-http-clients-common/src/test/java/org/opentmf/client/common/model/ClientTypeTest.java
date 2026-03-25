package org.opentmf.client.common.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class ClientTypeTest {

  @ParameterizedTest
  @CsvSource({
      "jdk, JDK",
      "JDK, JDK",
      "Jdk, JDK",
      "apache, APACHE",
      "APACHE, APACHE",
      "Apache, APACHE",
      "netty, NETTY",
      "NETTY, NETTY",
      "Netty, NETTY"
  })
  void fromString_canonicalNames_caseInsensitive(String input, ClientType expected) {
    assertThat(ClientType.fromString(input)).isEqualTo(expected);
  }

  @ParameterizedTest
  @CsvSource({
      "reactive, NETTY",
      "rest, JDK",
      "servlet, JDK"
  })
  void fromString_aliases(String alias, ClientType expected) {
    assertThat(ClientType.fromString(alias)).isEqualTo(expected);
  }

  @Test
  void fromString_trimWhitespace() {
    assertThat(ClientType.fromString("  jdk  ")).isEqualTo(ClientType.JDK);
    assertThat(ClientType.fromString(" reactive ")).isEqualTo(ClientType.NETTY);
  }

  @ParameterizedTest
  @ValueSource(strings = {"unknown", "webclient", "okhttp", ""})
  void fromString_invalidInput_throws(String input) {
    assertThatThrownBy(() -> ClientType.fromString(input))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void isReactive_onlyTrueForNetty() {
    assertThat(ClientType.NETTY.isReactive()).isTrue();
    assertThat(ClientType.JDK.isReactive()).isFalse();
    assertThat(ClientType.APACHE.isReactive()).isFalse();
  }
}
