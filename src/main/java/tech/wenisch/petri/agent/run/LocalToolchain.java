package tech.wenisch.petri.agent.run;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The unprivileged Alpine package prefix used when tests run in Petri's own container. Processes
 * started from it get a freshly built environment and never inherit Petri's variables or secrets.
 */
@Component
public class LocalToolchain {
  private final Path root;
  private final String apkExecutable;

  LocalToolchain(
      @Value("${petri.tests.toolchain-root:./data/toolchain}") String root,
      @Value("${petri.tests.apk-executable:apk}") String apkExecutable) {
    this.root = Path.of(root).toAbsolutePath().normalize();
    this.apkExecutable = apkExecutable;
  }

  Path root() { return root; }

  String apkExecutable() { return apkExecutable; }

  void ensureRoot() throws IOException {
    Files.createDirectories(root);
  }

  /** Clean environment for tests and installs; {@code extra} entries override the defaults. */
  Map<String, String> environment(Path home, Map<String, String> extra) {
    String javaHome = System.getProperty("java.home", "");
    Map<String, String> env = new LinkedHashMap<>();
    env.put("PATH", String.join(":",
        root + "/usr/local/bin", root + "/usr/bin", root + "/bin",
        javaHome + "/bin", "/usr/local/bin", "/usr/bin", "/bin"));
    env.put("LD_LIBRARY_PATH", root + "/usr/lib:" + root + "/lib");
    env.put("JAVA_HOME", javaHome);
    env.put("HOME", home.toString());
    env.put("TMPDIR", home.toString());
    env.put("JAVA_TOOL_OPTIONS", "-Djava.io.tmpdir=" + home);
    env.put("CI", "true");
    env.put("LC_ALL", "C");
    env.putAll(extra);
    return env;
  }
}
