package org.opentmf.client.test;

import org.springframework.context.ApplicationContext;
import org.springframework.web.client.RestClient;

/**
 * One call through a library-built synchronous client, picked by its bean name. Lets a test run
 * the same cell over several clients.
 */
final class SyncShapes {

  private SyncShapes() {
  }

  static String get(ApplicationContext context, String beanName, String url) {
    return context.getBean(beanName, RestClient.class).get().uri(url).retrieve()
        .body(String.class);
  }

  static String post(ApplicationContext context, String beanName, String url, String body) {
    return context.getBean(beanName, RestClient.class).post().uri(url).body(body).retrieve()
        .body(String.class);
  }
}
