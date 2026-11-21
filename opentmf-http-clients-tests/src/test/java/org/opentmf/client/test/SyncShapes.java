package org.opentmf.client.test;

import org.springframework.context.ApplicationContext;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

/**
 * One call through either shape of a library-built synchronous client, picked by its bean name:
 * a name ending in {@code RestTemplate} goes through the template, one ending in
 * {@code RestClient} through the client. Lets a test run the same cell over both shapes.
 */
final class SyncShapes {

  private SyncShapes() {
  }

  static String get(ApplicationContext context, String beanName, String url) {
    var bean = context.getBean(beanName);
    if (bean instanceof RestClient restClient) {
      return restClient.get().uri(url).retrieve().body(String.class);
    }
    return ((RestTemplate) bean).getForObject(url, String.class);
  }

  static String post(ApplicationContext context, String beanName, String url, String body) {
    var bean = context.getBean(beanName);
    if (bean instanceof RestClient restClient) {
      return restClient.post().uri(url).body(body).retrieve().body(String.class);
    }
    return ((RestTemplate) bean).postForObject(url, body, String.class);
  }
}
