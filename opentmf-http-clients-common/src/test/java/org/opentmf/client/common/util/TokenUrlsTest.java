package org.opentmf.client.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TokenUrlsTest {

  private static final String CANARY = "canaryuser:canarypass7f3a";

  @ParameterizedTest
  @ValueSource(strings = {"http://idp/token", "https://idp.example:8443/realms/r/token",
      "HTTPS://IDP/token", "http://" + CANARY + "@idp:8080/token", "http://10.0.0.1/token"})
  void anAbsoluteHttpUrlWithAHost_isUsable(String url) {
    assertThat(TokenUrls.isUsable(URI.create(url))).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {"htps://idp/token", "http://local%20host/token", "http:///token",
      "/token", "ftp://idp/token", "mailto:idp@example.com", "file:/token"})
  void anythingElse_isNotUsable(String url) {
    assertThat(TokenUrls.isUsable(URI.create(url))).isFalse();
  }

  @Test
  void theUserPart_isNotShown_everythingElseIs() {
    var url = URI.create("https://" + CANARY + "@idp.example:8443/realms/a%20b/token?x=1#f");

    assertThat(TokenUrls.shown(url))
        .isEqualTo(URI.create("https://idp.example:8443/realms/a%20b/token?x=1#f"));
  }

  @Test
  void aUrlWithoutAUserPart_isShownAsItIs() {
    var url = URI.create("http://idp/token");

    assertThat(TokenUrls.shown(url)).isSameAs(url);
    assertThat(TokenUrls.shown(null)).isNull();
  }

  @Test
  void aTextOfTheHttpClient_losesTheUserPart_rawOrDecoded() {
    var url = URI.create("http://canary%40user:canarypass7f3a@idp/token");
    var text = "I/O error on POST request for \"http://canary%40user:canarypass7f3a@idp/token\": "
        + "x; caused by: canary@user:canarypass7f3a@idp refused";

    assertThat(TokenUrls.scrubbed(text, url))
        .isEqualTo("I/O error on POST request for \"http://idp/token\": x; caused by: idp refused")
        .doesNotContain("canarypass7f3a");
    assertThat(TokenUrls.scrubbed("unchanged", URI.create("http://idp/token")))
        .isEqualTo("unchanged");
    assertThat(TokenUrls.scrubbed("unchanged", null)).isEqualTo("unchanged");
  }
}
