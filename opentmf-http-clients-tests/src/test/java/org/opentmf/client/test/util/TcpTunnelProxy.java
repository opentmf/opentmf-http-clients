package org.opentmf.client.test.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;

/**
 * Minimal HTTP CONNECT tunnel proxy for integration tests. Accepts CONNECT requests,
 * opens a raw TCP connection to the target, responds with {@code 200 Connection Established},
 * and blindly relays bytes in both directions. Never terminates or inspects TLS traffic,
 * so the TLS handshake (including mTLS certificate exchange) happens end-to-end between
 * the client and the target server through the tunnel.
 */
@Slf4j
public final class TcpTunnelProxy {

  private final ServerSocket serverSocket;
  private final ExecutorService executor;
  private final AtomicBoolean running = new AtomicBoolean(false);

  public TcpTunnelProxy() {
    try {
      this.serverSocket = new ServerSocket(0);
    } catch (IOException e) {
      throw new IllegalStateException("Failed to bind proxy server socket", e);
    }
    this.executor = Executors.newCachedThreadPool(r -> {
      var t = new Thread(r, "tcp-tunnel-proxy");
      t.setDaemon(true);
      return t;
    });
  }

  public int getPort() {
    return serverSocket.getLocalPort();
  }

  public void start() {
    if (!running.compareAndSet(false, true)) {
      return;
    }
    executor.submit(this::acceptLoop);
    log.info("TcpTunnelProxy started on port {}", getPort());
  }

  public void stop() {
    running.set(false);
    try {
      serverSocket.close();
    } catch (IOException e) {
      log.debug("Error closing proxy server socket", e);
    }
    executor.shutdownNow();
    log.info("TcpTunnelProxy stopped");
  }

  private void acceptLoop() {
    while (running.get()) {
      try {
        var clientSocket = serverSocket.accept();
        executor.submit(() -> handleConnection(clientSocket));
      } catch (IOException e) {
        if (running.get()) {
          log.warn("Error accepting connection", e);
        }
      }
    }
  }

  private void handleConnection(Socket clientSocket) {
    try (clientSocket) {
      var reader = new BufferedReader(
          new InputStreamReader(clientSocket.getInputStream(), StandardCharsets.ISO_8859_1));

      String connectLine = reader.readLine();
      if (connectLine == null || !connectLine.toUpperCase().startsWith("CONNECT ")) {
        return;
      }

      // Parse "CONNECT host:port HTTP/1.1"
      String[] parts = connectLine.split(" ");
      String[] hostPort = parts[1].split(":");
      String targetHost = hostPort[0];
      int targetPort = Integer.parseInt(hostPort[1]);

      // Consume remaining headers until blank line
      String line;
      while ((line = reader.readLine()) != null && !line.isEmpty()) {
        // discard headers
      }

      var targetSocket = new Socket(targetHost, targetPort);

      // Send 200 back to client
      OutputStream clientOut = clientSocket.getOutputStream();
      clientOut.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
      clientOut.flush();

      // Relay bytes bidirectionally
      var clientToTarget = executor.submit(() ->
          relay(clientSocket, targetSocket, "client->target"));
      var targetToClient = executor.submit(() ->
          relay(targetSocket, clientSocket, "target->client"));

      clientToTarget.get();
      targetToClient.get();
    } catch (Exception e) {
      log.debug("Tunnel connection ended: {}", e.getMessage());
    }
  }

  private static void relay(Socket from, Socket to, String direction) {
    try (var in = from.getInputStream(); var out = to.getOutputStream()) {
      byte[] buf = new byte[8192];
      int n;
      while ((n = in.read(buf)) != -1) {
        out.write(buf, 0, n);
        out.flush();
      }
    } catch (IOException e) {
      log.debug("Relay {} ended: {}", direction, e.getMessage());
    } finally {
      closeQuietly(from);
      closeQuietly(to);
    }
  }

  private static void closeQuietly(Socket socket) {
    try {
      if (!socket.isClosed()) {
        socket.close();
      }
    } catch (IOException ignored) {
      // intentionally ignored
    }
  }
}
