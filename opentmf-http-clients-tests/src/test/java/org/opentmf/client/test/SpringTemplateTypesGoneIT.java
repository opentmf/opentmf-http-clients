package org.opentmf.client.test;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * No class the library ships, and no test class of its modules, references Spring's
 * {@code RestTemplate}, {@code RestOperations} or {@code ResponseErrorHandler}: Spring Framework
 * 7.1 marks them for removal in 8.0, and 3.0.0 removed every entry point built on them. The
 * cell reads the built classes byte by byte — the library's jars on this module's classpath and
 * every module's test classes — so a reference cannot come back unnoticed.
 */
class SpringTemplateTypesGoneIT {

  private static final String PACKAGE = String.join("", "org/springframework/web/client", "/");

  /** Spring's types, each as a class file names it; built at run time so this class has none. */
  private static final List<String> GONE = Stream.of("RestTemplate", "RestOperations",
      "ResponseErrorHandler").map(PACKAGE::concat).toList();

  private static final List<String> MAIN_MODULES = List.of("opentmf-http-clients-common",
      "opentmf-http-clients-rest", "opentmf-http-clients-reactive",
      "opentmf-http-clients-bearer-provider", "opentmf-http-clients-autoconfigure");

  private static final List<String> TEST_MODULES = Stream.concat(MAIN_MODULES.stream(),
      Stream.of("opentmf-http-clients-tests")).toList();

  @Test
  void theLibrarysClasses_referenceNoneOfSpringsTemplateTypes() {
    var scanned = new TreeMap<String, Integer>();
    var references = new ArrayList<String>();
    for (var entry : classpathEntries()) {
      var module = MAIN_MODULES.stream().filter(entry.toString()::contains).findFirst();
      module.ifPresent(name -> scanned.merge(name, scan(entry, references), Integer::sum));
    }

    assertThat(scanned).as("classes read per library module on the classpath")
        .containsOnlyKeys(MAIN_MODULES)
        .allSatisfy((module, count) -> assertThat(count).as(module).isPositive());
    assertThat(references).as("classes that reference %s", GONE).isEmpty();
  }

  @Test
  void theModulesTestClasses_referenceNoneOfSpringsTemplateTypes() {
    var scanned = new TreeMap<String, Integer>();
    var references = new ArrayList<String>();
    var root = Path.of("").toAbsolutePath().getParent();
    for (var module : TEST_MODULES) {
      var testClasses = root.resolve(module).resolve("target/test-classes");
      assertThat(testClasses).as("test classes of %s", module).isDirectory();
      scanned.put(module, scan(testClasses, references));
    }

    assertThat(scanned).as("test classes read per module")
        .allSatisfy((module, count) -> assertThat(count).as(module).isPositive());
    assertThat(references).as("test classes that reference %s", GONE).isEmpty();
  }

  private static List<Path> classpathEntries() {
    return Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
        .map(Path::of)
        .toList();
  }

  /** Reads every class file under the entry; returns how many it read. */
  private static int scan(Path entry, List<String> references) {
    if (Files.isDirectory(entry)) {
      try (var files = Files.walk(entry)) {
        var classes = files.filter(file -> file.toString().endsWith(".class")).toList();
        for (var file : classes) {
          check(entry.relativize(file).toString(), Files.readAllBytes(file), references);
        }
        return classes.size();
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    try (var jar = new JarFile(entry.toFile())) {
      int count = 0;
      for (var it = jar.entries().asIterator(); it.hasNext(); ) {
        var jarEntry = it.next();
        if (jarEntry.getName().endsWith(".class")) {
          try (InputStream in = jar.getInputStream(jarEntry)) {
            check(entry.getFileName() + "!" + jarEntry.getName(), in.readAllBytes(), references);
          }
          count++;
        }
      }
      return count;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /** A class file names a type in its constant pool, as plain ASCII bytes. */
  private static void check(String name, byte[] bytes, List<String> references) {
    var text = new String(bytes, StandardCharsets.ISO_8859_1);
    GONE.stream().filter(text::contains)
        .forEach(type -> references.add(name + " -> " + type.replace('/', '.')));
  }
}
