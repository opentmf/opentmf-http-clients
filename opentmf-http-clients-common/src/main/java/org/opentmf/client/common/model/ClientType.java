package org.opentmf.client.common.model;

import java.util.Locale;
import java.util.Map;

/**
 * Determines which HTTP client implementation to create.
 *
 * <ul>
 *   <li>{@link #JDK} — synchronous {@code RestTemplate} backed by the JDK {@code HttpClient}
 *       (default)</li>
 *   <li>{@link #APACHE} — synchronous {@code RestTemplate} backed by Apache HttpClient 5</li>
 *   <li>{@link #NETTY} — reactive {@code WebClient} backed by Reactor Netty</li>
 * </ul>
 *
 * <p>The aliases {@code reactive} (→ {@link #NETTY}), {@code rest} (→ {@link #JDK}),
 * and {@code servlet} (→ {@link #JDK}) are accepted by {@link #fromString(String)}.</p>
 */
public enum ClientType {
  JDK,
  APACHE,
  NETTY;

  private static final Map<String, ClientType> ALIASES = Map.of(
      "reactive", NETTY,
      "rest", JDK,
      "servlet", JDK
  );

  /**
   * Resolves a client type from a string, supporting canonical names and aliases.
   *
   * @throws IllegalArgumentException if the value is not a known name or alias
   */
  public static ClientType fromString(String value) {
    String key = value.trim().toLowerCase(Locale.ROOT);
    ClientType alias = ALIASES.get(key);
    if (alias != null) {
      return alias;
    }
    return valueOf(value.trim().toUpperCase(Locale.ROOT));
  }

  public boolean isReactive() {
    return this == NETTY;
  }
}
