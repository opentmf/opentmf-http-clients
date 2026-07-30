package org.opentmf.client.starter.registry;

import org.jspecify.annotations.Nullable;
import reactor.netty.resources.ConnectionProvider;

/**
 * Builds the close action for a dynamically registered reactive client. Exists as its own class
 * ON PURPOSE: the closer lambda captures {@link ConnectionProvider} (reactor-netty, an OPTIONAL
 * dependency), and a lambda declared inside {@code HttpClientRegistry} would compile to a
 * synthetic method of the registry whose descriptor references reactor-netty — breaking
 * {@code getDeclaredMethods()} introspection (and therefore every Spring context) on classpaths
 * without reactor-netty. This class is only loaded when a reactive entry is actually built.
 */
final class ReactiveEntryCloser {

  private ReactiveEntryCloser() {}

  static Runnable of(ConnectionProvider connectionProvider,
      @Nullable ConnectionProvider tokenConnectionProvider) {
    return () -> {
      connectionProvider.disposeLater().subscribe();
      if (tokenConnectionProvider != null) {
        tokenConnectionProvider.disposeLater().subscribe();
      }
    };
  }
}
