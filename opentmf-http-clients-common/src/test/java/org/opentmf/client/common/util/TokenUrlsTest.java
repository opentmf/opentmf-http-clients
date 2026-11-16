package org.opentmf.client.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class TokenUrlsTest {

  private static final String CANARY = "canaryuser:canarypass7f3a";
  private static final String QUERY_CANARY = "canaryquery9b2e";

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

  @ParameterizedTest(name = "{0}")
  @CsvSource(delimiter = '|', value = {
      "https://" + CANARY + "@idp.example:8443/realms/a%20b/token"
          + "|https://idp.example:8443/realms/a%20b/token",
      "https://idp.example:8443/token?client_secret=" + QUERY_CANARY
          + "|https://idp.example:8443/token",
      "http://" + CANARY + "@idp/token?client_secret=" + QUERY_CANARY + "#f|http://idp/token",
      "http://idp/token#" + QUERY_CANARY + "|http://idp/token",
      "http://idp/token?a%26b=c%3D" + QUERY_CANARY + "|http://idp/token",
      "http://idp/token?|http://idp/token",
      "http://idp|http://idp",
      "/token?client_secret=" + QUERY_CANARY + "|/token",
      "mailto:" + QUERY_CANARY + "@idp|mailto:" + TokenUrls.WITHHELD})
  void onlyTheSchemeHostPortAndPath_areShown(String configured, String expected) {
    var shown = TokenUrls.shown(URI.create(configured));

    assertThat(shown).isEqualTo(URI.create(expected));
    assertThat(String.valueOf(shown)).doesNotContain(QUERY_CANARY)
        .doesNotContain("canarypass7f3a");
  }

  @Test
  void aUrlWithoutAUserPartQueryOrFragment_isShownAsItIs() {
    var url = URI.create("https://idp.example:8443/realms/a%20b/token");

    assertThat(TokenUrls.isShownAsItIs(url)).isTrue();
    assertThat(TokenUrls.shown(url)).isSameAs(url);
    assertThat(TokenUrls.shown(null)).isNull();
    assertThat(TokenUrls.isShownAsItIs(URI.create("http://idp/token?p=policy"))).isFalse();
    assertThat(TokenUrls.isShownAsItIs(URI.create("http://idp/token#f"))).isFalse();
    assertThat(TokenUrls.isShownAsItIs(URI.create("http://" + CANARY + "@idp/token"))).isFalse();
    assertThat(TokenUrls.isShownAsItIs(URI.create("mailto:x@idp"))).isFalse();
  }

  @Test
  void aTextOfTheHttpClient_losesTheQueryAndFragment_rawOrDecoded() {
    var url = URI.create("http://idp/token?client_secret=" + QUERY_CANARY + "%26x#frag%20"
        + QUERY_CANARY);
    var text = "I/O error on POST request for \"http://idp/token?client_secret=" + QUERY_CANARY
        + "%26x#frag%20" + QUERY_CANARY + "\": x; caused by: refused at /token?client_secret="
        + QUERY_CANARY + "&x#frag " + QUERY_CANARY;

    assertThat(TokenUrls.scrubbed(text, url))
        .isEqualTo("I/O error on POST request for \"http://idp/token\": x; caused by: refused at "
            + "/token")
        .doesNotContain(QUERY_CANARY);
    assertThat(TokenUrls.scrubbed("from idp: fine", URI.create("http://idp/token#f")))
        .as("a fragment is removed only with its #").isEqualTo("from idp: fine");
    assertThat(TokenUrls.scrubbed("a ? stays", URI.create("http://idp/token?")))
        .isEqualTo("a ? stays");
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
