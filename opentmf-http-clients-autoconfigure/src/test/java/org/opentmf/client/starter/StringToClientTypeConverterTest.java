package org.opentmf.client.starter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.opentmf.client.common.model.ClientType;

class StringToClientTypeConverterTest {

  private final StringToClientTypeConverter converter = new StringToClientTypeConverter();

  @ParameterizedTest
  @CsvSource({
      "jdk, JDK",
      "JDK, JDK",
      "apache, APACHE",
      "APACHE, APACHE",
      "netty, NETTY",
      "NETTY, NETTY"
  })
  void convert_canonicalNames(String input, ClientType expected) {
    assertThat(converter.convert(input)).isEqualTo(expected);
  }

  @ParameterizedTest
  @CsvSource({
      "reactive, NETTY",
      "rest, JDK",
      "servlet, JDK"
  })
  void convert_aliases(String input, ClientType expected) {
    assertThat(converter.convert(input)).isEqualTo(expected);
  }

  @Test
  void convert_invalidInput_throws() {
    assertThatThrownBy(() -> converter.convert("invalid"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
