package org.opentmf.client.starter;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * A plain-socket HTTP peer that answers {@code 200} and holds back bytes at chosen moments, so a
 * test can tell a deadline counted from the send from a silence counted since the last byte.
 * Writes are scheduled on one thread, never slept for, so they leave in order at the moments
 * given. A connection stays open after its last write until {@link #closeConnections()}.
 */
final class SlowPeer implements AutoCloseable {

  static final long HEADER_DELAY_MS = 1200;
  static final long TRICKLE_GAP_MS = 700;
  static final int TRICKLE_BYTES = 8;
  static final long LATE_HEADERS_MS = 4000;

  enum Behaviour {
    /** Headers after {@value #HEADER_DELAY_MS} ms with 8 of 64 body bytes, then silence. */
    STALL,
    /** Headers at once, then one of 8 body bytes every {@value #TRICKLE_GAP_MS} ms. */
    TRICKLE,
    /** Headers and the whole 8-byte body at once. */
    COMPLETE,
    /** Headers and the whole body after {@value #LATE_HEADERS_MS} ms. */
    LATE_HEADERS
  }

  private final ServerSocket server;
  private final ScheduledExecutorService writes;
  private final List<Socket> accepted = new CopyOnWriteArrayList<>();
  private volatile Behaviour behaviour = Behaviour.COMPLETE;

  private SlowPeer(ServerSocket server) {
    this.server = server;
    this.writes = Executors.newSingleThreadScheduledExecutor(task -> {
      var thread = new Thread(task, "slow-peer-writes");
      thread.setDaemon(true);
      return thread;
    });
    var acceptor = new Thread(this::acceptLoop, "slow-peer");
    acceptor.setDaemon(true);
    acceptor.start();
  }

  static SlowPeer start() throws IOException {
    return new SlowPeer(new ServerSocket(0));
  }

  int port() {
    return server.getLocalPort();
  }

  String baseUrl() {
    return "http://localhost:" + port();
  }

  void behave(Behaviour how) {
    behaviour = how;
  }

  void closeConnections() {
    for (var socket : accepted) {
      try {
        socket.close();
      } catch (IOException ignored) {
        // already gone
      }
    }
    accepted.clear();
  }

  @Override
  public void close() throws IOException {
    closeConnections();
    server.close();
    writes.shutdownNow();
  }

  private void acceptLoop() {
    while (!server.isClosed()) {
      try {
        var socket = server.accept();
        accepted.add(socket);
        var how = behaviour;
        var answer = new Thread(() -> answer(socket, how), "slow-peer-answer");
        answer.setDaemon(true);
        answer.start();
      } catch (IOException closed) {
        return;
      }
    }
  }

  private void answer(Socket socket, Behaviour how) {
    try {
      readRequestHead(socket.getInputStream());
    } catch (IOException gone) {
      return;
    }
    switch (how) {
      case COMPLETE -> send(socket, 0, head(TRICKLE_BYTES) + "abcdefgh");
      case LATE_HEADERS -> send(socket, LATE_HEADERS_MS, head(TRICKLE_BYTES) + "abcdefgh");
      case STALL -> send(socket, HEADER_DELAY_MS, head(64) + "partial!");
      case TRICKLE -> {
        send(socket, 0, head(TRICKLE_BYTES));
        for (int i = 0; i < TRICKLE_BYTES; i++) {
          send(socket, i * TRICKLE_GAP_MS, String.valueOf((char) ('a' + i)));
        }
      }
    }
  }

  private void send(Socket socket, long afterMillis, String text) {
    writes.schedule(() -> {
      try {
        var out = socket.getOutputStream();
        out.write(text.getBytes(StandardCharsets.US_ASCII));
        out.flush();
      } catch (IOException gone) {
        // the client closed the connection: expected once it gives up
      }
    }, afterMillis, TimeUnit.MILLISECONDS);
  }

  /** Reads the request head; a request body (the token form) is left unread. */
  private static void readRequestHead(InputStream in) throws IOException {
    int matched = 0;
    byte[] end = {'\r', '\n', '\r', '\n'};
    int b;
    while (matched < end.length && (b = in.read()) != -1) {
      matched = b == end[matched] ? matched + 1 : (b == '\r' ? 1 : 0);
    }
  }

  private static String head(int contentLength) {
    return "HTTP/1.1 200 OK\r\n"
        + "Content-Type: text/plain\r\n"
        + "Content-Length: " + contentLength + "\r\n\r\n";
  }
}
