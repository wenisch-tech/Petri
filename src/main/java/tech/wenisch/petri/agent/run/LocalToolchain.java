package tech.wenisch.petri.agent.run;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
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
  private final CommandEnvironment commandEnvironment;
  private final tech.wenisch.petri.packages.PackageEnvironment packages;

  @Autowired
  LocalToolchain(
      @Value("${petri.tests.toolchain-root:./data/toolchain}") String root,
      @Value("${petri.tests.apk-executable:apk}") String apkExecutable,
      CommandEnvironment commandEnvironment,
      org.springframework.beans.factory.ObjectProvider<tech.wenisch.petri.packages.PackageEnvironment>
          packages) {
    this(root, apkExecutable, commandEnvironment, packages.getIfAvailable());
  }

  LocalToolchain(String root, String apkExecutable, CommandEnvironment commandEnvironment,
      tech.wenisch.petri.packages.PackageEnvironment packages) {
    this.packages = packages;
    this.root = Path.of(root).toAbsolutePath().normalize();
    this.apkExecutable = apkExecutable;
    this.commandEnvironment = commandEnvironment;
  }

  LocalToolchain(String root, String apkExecutable, CommandEnvironment commandEnvironment) {
    this(root, apkExecutable, commandEnvironment, (tech.wenisch.petri.packages.PackageEnvironment) null);
  }

  LocalToolchain(String root, String apkExecutable) {
    this(root, apkExecutable, CommandEnvironment.none(), (tech.wenisch.petri.packages.PackageEnvironment) null);
  }

  Path root() { return root; }

  String apkExecutable() { return apkExecutable; }

  void ensureRoot() throws IOException {
    Files.createDirectories(root);
  }

  private static final List<String> EXECUTABLE_DIRECTORIES = List.of(
      "bin", "sbin", "usr/bin", "usr/sbin", "usr/local/bin");

  /**
   * apk installs into a prefix, but package symlinks are absolute: {@code /usr/bin/mvn} points at
   * {@code /usr/share/java/maven-3/bin/mvn}, which does not exist outside the prefix, so the tool
   * is "installed" yet cannot be found or run. Absolute links whose target is missing on the real
   * filesystem but present inside the prefix are rewritten as relative links. Returns the number of
   * links repaired.
   */
  int repairLinks() throws IOException {
    int repaired = 0;
    for (String directory : EXECUTABLE_DIRECTORIES) {
      Path base = root.resolve(directory);
      if (!Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS)) continue;
      try (var entries = Files.newDirectoryStream(base)) {
        for (Path link : entries) {
          if (!Files.isSymbolicLink(link)) continue;
          Path target = Files.readSymbolicLink(link);
          if (!target.isAbsolute() || Files.exists(target)) continue;
          Path inside = root.resolve(target.toString().substring(1)).normalize();
          if (!inside.startsWith(root) || !Files.exists(inside)) continue;
          Path relative = link.getParent().relativize(inside);
          Files.delete(link);
          Files.createSymbolicLink(link, relative);
          repaired++;
        }
      }
    }
    return repaired;
  }

  /** Whether a runnable tool of that name is installed in the toolchain prefix. */
  boolean hasTool(String name) {
    for (String directory : EXECUTABLE_DIRECTORIES) {
      Path candidate = root.resolve(directory).resolve(name);
      if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return true;
    }
    return false;
  }

  /** TMPDIR and friends pointing at a worktree's scratch directory. */
  static Map<String, String> temporaryDirectoryEnvironment(Path scratch) {
    Map<String, String> env = new LinkedHashMap<>();
    env.put("TMPDIR", scratch.toString());
    env.put("TMP", scratch.toString());
    env.put("TEMP", scratch.toString());
    env.put("JAVA_TOOL_OPTIONS", "-Djava.io.tmpdir=" + scratch);
    return env;
  }

  /** Clean environment for tests and installs; {@code extra} entries override the defaults. */
  Map<String, String> environment(Path home, Map<String, String> extra) {
    return environment(home, extra, Map.of());
  }

  /**
   * As above, with configured command variables (proxy forwarding, instance and profile variables)
   * layered between the defaults and {@code extra}. Reserved names never reach this layer.
   */
  Map<String, String> environment(
      Path home, Map<String, String> extra, Map<String, String> profileVariables) {
    return environment(home, extra, profileVariables, null);
  }

  /**
   * As above, with the package remotes that apply to the project (the instance's when null): the
   * generated tool configuration wins over variables a user could set.
   */
  Map<String, String> environment(
      Path home, Map<String, String> extra, Map<String, String> profileVariables,
      java.util.UUID projectId) {
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
    env.putAll(commandEnvironment.extra(profileVariables));
    if (packages != null) env.putAll(packages.environment(projectId, home));
    env.putAll(extra);
    if (packages != null) packages.placeToolFiles(home, env);
    return env;
  }
}
