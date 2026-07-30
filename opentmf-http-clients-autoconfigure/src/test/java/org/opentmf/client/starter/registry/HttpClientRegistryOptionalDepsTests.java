package org.opentmf.client.starter.registry;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Regression guard for the 2.1.4 defect where a closer lambda declared inside
 * {@link HttpClientRegistry} compiled to a synthetic method whose descriptor referenced
 * {@code reactor.netty.resources.ConnectionProvider}. Because reactor-netty is an OPTIONAL
 * dependency, {@code getDeclaredMethods()} — which Spring runs on every bean class — resolved the
 * parameter type and threw {@code NoClassDefFoundError} on every classpath without reactor-netty,
 * killing the consumer's whole ApplicationContext.
 *
 * <p>The test scans the compiled class file's constant pool for method descriptors (synthetic
 * lambda methods included — reflection could not see the failure on THIS classpath, where
 * reactor-netty is present) and asserts none of them mentions an optional-dependency package.
 * Optional types may be used in method BODIES (resolved lazily at first execution) and in
 * separate, lazily-loaded classes such as {@link ReactiveEntryCloser} — just never in a method
 * signature of the registry itself.
 */
class HttpClientRegistryOptionalDepsTests {

  private static final List<String> OPTIONAL_PACKAGES =
      List.of(
          "Lreactor/netty/",
          "Lorg/springframework/web/reactive/",
          "Lorg/opentmf/client/rest/",
          "Lorg/opentmf/client/reactive/");

  @Test
  void registryMethodDescriptorsReferenceNoOptionalDependencyTypes() throws IOException {
    for (String descriptor : methodDescriptorsOf(HttpClientRegistry.class)) {
      for (String optionalPackage : OPTIONAL_PACKAGES) {
        assertThat(descriptor)
            .as("method descriptor %s must not reference optional package %s — it breaks "
                + "getDeclaredMethods() when the optional dependency is absent",
                descriptor, optionalPackage)
            .doesNotContain(optionalPackage);
      }
    }
  }

  /**
   * Minimal class-file parse of the method_info section — exactly the descriptors
   * {@code getDeclaredMethods()} resolves eagerly. Reflection cannot detect the defect on THIS
   * test classpath (reactor-netty is present here), hence the bytecode-level check.
   */
  private static List<String> methodDescriptorsOf(Class<?> type) throws IOException {
    String resource = type.getName().replace('.', '/') + ".class";
    try (InputStream in = type.getClassLoader().getResourceAsStream(resource)) {
      assertThat(in).as("class file resource %s", resource).isNotNull();
      return parseDeclaredMethodDescriptors(new DataInputStream(in));
    }
  }

  private static List<String> parseDeclaredMethodDescriptors(DataInputStream in)
      throws IOException {
    in.readInt(); // magic
    in.readUnsignedShort(); // minor
    in.readUnsignedShort(); // major
    int constantPoolCount = in.readUnsignedShort();
    String[] utf8 = new String[constantPoolCount];
    for (int i = 1; i < constantPoolCount; i++) {
      int tag = in.readUnsignedByte();
      switch (tag) {
        case 1 -> utf8[i] = in.readUTF();
        case 7, 8, 16, 19, 20 -> in.readUnsignedShort();
        case 15 -> in.skipBytes(3);
        case 3, 4, 9, 10, 11, 12, 17, 18 -> in.readInt();
        case 5, 6 -> {
          in.readLong();
          i++; // longs/doubles occupy two constant-pool slots
        }
        default -> throw new IOException("Unknown constant-pool tag " + tag);
      }
    }
    in.readUnsignedShort(); // access flags
    in.readUnsignedShort(); // this class
    in.readUnsignedShort(); // super class
    int interfaces = in.readUnsignedShort();
    in.skipBytes(interfaces * 2);
    skipMembers(in); // fields
    List<String> descriptors = new ArrayList<>();
    int methods = in.readUnsignedShort();
    for (int i = 0; i < methods; i++) {
      in.readUnsignedShort(); // access flags
      in.readUnsignedShort(); // name index
      descriptors.add(utf8[in.readUnsignedShort()]);
      skipAttributes(in);
    }
    return descriptors;
  }

  private static void skipMembers(DataInputStream in) throws IOException {
    int count = in.readUnsignedShort();
    for (int i = 0; i < count; i++) {
      in.skipBytes(6); // access flags + name index + descriptor index
      skipAttributes(in);
    }
  }

  private static void skipAttributes(DataInputStream in) throws IOException {
    int count = in.readUnsignedShort();
    for (int i = 0; i < count; i++) {
      in.readUnsignedShort(); // attribute name index
      int length = in.readInt();
      in.skipBytes(length);
    }
  }
}
