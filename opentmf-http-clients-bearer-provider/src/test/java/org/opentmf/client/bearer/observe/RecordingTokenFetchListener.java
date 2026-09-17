package org.opentmf.client.bearer.observe;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Test double that keeps every notification in order. */
public class RecordingTokenFetchListener implements TokenFetchListener {

  public record Notification(URI tokenUrl, Outcome outcome) {
  }

  private final List<Notification> notifications = new CopyOnWriteArrayList<>();

  @Override
  public void onTokenFetch(URI tokenUrl, Outcome outcome) {
    notifications.add(new Notification(tokenUrl, outcome));
  }

  public List<Outcome> outcomes() {
    var outcomes = new ArrayList<Outcome>();
    notifications.forEach(n -> outcomes.add(n.outcome()));
    return outcomes;
  }

  public List<Notification> notifications() {
    return List.copyOf(notifications);
  }
}
