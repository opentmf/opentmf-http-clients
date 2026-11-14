package org.opentmf.client.starter.reactive;

import java.util.function.Consumer;
import reactor.netty.Connection;

/**
 * Bridge bean that wraps a Logbook Netty handler as a {@code Consumer<Connection>}, and the one of
 * a client's token calls (whose Logbook never sees the token endpoint's response body).
 * Injected via {@code ObjectProvider<ReactiveLogbookSupport>} so that the registrar
 * never references Logbook types in its constructor signature, avoiding
 * {@code TypeNotPresentException} when Logbook is absent from the classpath.
 */
public class ReactiveLogbookSupport {

  private final Consumer<Connection> handlerAdder;
  private final Consumer<Connection> tokenCallHandlerAdder;

  /** The same handler for a client's calls and its token calls. */
  public ReactiveLogbookSupport(Consumer<Connection> handlerAdder) {
    this(handlerAdder, handlerAdder);
  }

  /**
   * @param handlerAdder          for a client's own connections
   * @param tokenCallHandlerAdder for the connections of its token calls
   */
  public ReactiveLogbookSupport(Consumer<Connection> handlerAdder,
      Consumer<Connection> tokenCallHandlerAdder) {
    this.handlerAdder = handlerAdder;
    this.tokenCallHandlerAdder = tokenCallHandlerAdder;
  }

  public void addHandler(Connection connection) {
    handlerAdder.accept(connection);
  }

  public void addTokenCallHandler(Connection connection) {
    tokenCallHandlerAdder.accept(connection);
  }
}
