package org.opentmf.client.bearer;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A plain-socket token endpoint that counts the token calls it receives and holds each answer
 * back for a chosen time, so concurrent callers are all waiting while a mint is in flight. Every
 * answer closes its connection, so one call is one connection. Answers are scheduled on one
 * thread, never slept for.
 */
public final class TokenPeer implements AutoCloseable {

  /** How the endpoint answers. */
  public enum Answer {
    /** {@code 200} with a token that expires in {@value #EXPIRES_IN} seconds. */
    TOKEN,
    /** {@code 503}, with a {@code Retry-After} when one is set. */
    UNAVAILABLE,
    /** {@code 401} with {@code {"error":"invalid_client"}}: a refused credential. */
    REFUSED,
    /** The connection is closed without an answer: a transport failure. */
    CLOSE
  }

  public static final long EXPIRES_IN = 300;

  private final ServerSocket server;
  private final ScheduledExecutorService answers;
  private final AtomicInteger calls = new AtomicInteger();
  private final List<Long> arrivals = new CopyOnWriteArrayList<>();
  private volatile Answer answer = Answer.TOKEN;
  private volatile long holdMillis;
  private volatile Long retryAfterSeconds;

  private TokenPeer(ServerSocket server) {
    this.server = server;
    this.answers = Executors.newSingleThreadScheduledExecutor(task -> {
      var thread = new Thread(task, "token-peer-answers");
      thread.setDaemon(true);
      return thread;
    });
    var acceptor = new Thread(this::acceptLoop, "token-peer");
    acceptor.setDaemon(true);
    acceptor.start();
  }

  public static TokenPeer start() throws IOException {
    return new TokenPeer(new ServerSocket(0, 512));
  }

  public URI tokenUrl() {
    return URI.create("http://localhost:" + server.getLocalPort() + "/realms/r/token");
  }

  /** Answers every following call so, each after {@code holdMillis}. */
  public TokenPeer answer(Answer how, long holdMillis) {
    this.answer = how;
    this.holdMillis = holdMillis;
    return this;
  }

  /** The {@code Retry-After} of a {@link Answer#UNAVAILABLE}, or none when {@code null}. */
  public TokenPeer retryAfterSeconds(Long seconds) {
    this.retryAfterSeconds = seconds;
    return this;
  }

  /** The token calls received so far. */
  public int calls() {
    return calls.get();
  }

  /** When each call arrived, in {@link System#nanoTime()}. */
  public List<Long> arrivals() {
    return List.copyOf(arrivals);
  }

  public void reset() {
    calls.set(0);
    arrivals.clear();
    retryAfterSeconds = null;
  }

  @Override
  public void close() throws IOException {
    server.close();
    answers.shutdownNow();
  }

  private void acceptLoop() {
    while (!server.isClosed()) {
      try {
        var socket = server.accept();
        var reader = new Thread(() -> serve(socket), "token-peer-call");
        reader.setDaemon(true);
        reader.start();
      } catch (IOException closed) {
        return;
      }
    }
  }

  private void serve(Socket socket) {
    try {
      var in = socket.getInputStream();
      in.readNBytes(readHead(in));
    } catch (IOException gone) {
      close(socket);
      return;
    }
    arrivals.add(System.nanoTime());
    calls.incrementAndGet();
    var how = answer;
    var retryAfter = retryAfterSeconds;
    answers.schedule(() -> write(socket, how, retryAfter), holdMillis, TimeUnit.MILLISECONDS);
  }

  private static void write(Socket socket, Answer how, Long retryAfter) {
    try (socket) {
      var text = switch (how) {
        case TOKEN -> response("200 OK", "{\"access_token\":\"tok\",\"expires_in\":" + EXPIRES_IN
            + "}", "");
        case UNAVAILABLE -> response("503 Service Unavailable", "",
            retryAfter == null ? "" : "Retry-After: " + retryAfter + "\r\n");
        case REFUSED -> response("401 Unauthorized", "{\"error\":\"invalid_client\"}", "");
        case CLOSE -> null;
      };
      if (text != null) {
        var out = socket.getOutputStream();
        out.write(text.getBytes(StandardCharsets.US_ASCII));
        out.flush();
      }
    } catch (IOException gone) {
      // the client gave up
    }
  }

  private static String response(String status, String body, String extraHeaders) {
    return "HTTP/1.1 " + status + "\r\n"
        + "Content-Type: application/json\r\n"
        + extraHeaders
        + "Content-Length: " + body.length() + "\r\n"
        + "Connection: close\r\n\r\n"
        + body;
  }

  private static void close(Socket socket) {
    try {
      socket.close();
    } catch (IOException ignored) {
      // already gone
    }
  }

  /** Reads the request head and returns its {@code Content-Length}. */
  private static int readHead(InputStream in) throws IOException {
    var head = new StringBuilder();
    int b;
    while ((b = in.read()) != -1) {
      head.append((char) b);
      if (head.length() >= 4 && head.lastIndexOf("\r\n\r\n") == head.length() - 4) {
        break;
      }
    }
    for (var line : head.toString().split("\r\n")) {
      if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
        return Integer.parseInt(line.substring("content-length:".length()).trim());
      }
    }
    return 0;
  }
}
