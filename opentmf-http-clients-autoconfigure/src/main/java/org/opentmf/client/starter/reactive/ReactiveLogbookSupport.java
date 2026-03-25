package org.opentmf.client.starter.reactive;

import java.util.function.Consumer;
import reactor.netty.Connection;

/**
 * Bridge bean that wraps a Logbook Netty handler as a {@code Consumer<Connection>}.
 * Injected via {@code ObjectProvider<ReactiveLogbookSupport>} so that the registrar
 * never references Logbook types in its constructor signature, avoiding
 * {@code TypeNotPresentException} when Logbook is absent from the classpath.
 */
public class ReactiveLogbookSupport {

  private final Consumer<Connection> handlerAdder;

  public ReactiveLogbookSupport(Consumer<Connection> handlerAdder) {
    this.handlerAdder = handlerAdder;
  }

  public void addHandler(Connection connection) {
    handlerAdder.accept(connection);
  }
}
