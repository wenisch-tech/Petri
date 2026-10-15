package tech.wenisch.petri.agent.run;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import tech.wenisch.petri.agent.run.TrivyScanService.Resolution;

/**
 * Prepares a project for a dependency scan with whatever package manager it uses: it finds the
 * manifests, and for those without a lockfile runs the ecosystem's own tool to produce the resolved
 * dependency set. The work happens in a throw-away copy of the project, so the repository is never
 * changed, and under the same confinement as test runs. Nothing is installed or executed from the
 * project (npm runs with {@code --ignore-scripts}, Composer with {@code --no-scripts}).
 */
@Component
class DependencyResolver {
  static final String RESOLVED = "resolved";
  static final String NOT_NEEDED = "not-needed";
  static final String TOOL_MISSING = "tool-missing";
  static final String FAILED = "failed";
  static final String SKIPPED = "skipped";

  private static final Set<String> SKIP_DIRECTORIES = Set.of(
      ".git", "node_modules", "target", "build", ".gradle", ".idea", ".venv", "venv",
      "__pycache__", "dist", "out", ".next", ".cache");
  private static final long MAX_BYTES = 500L * 1024 * 1024;
  private static final int MAX_FILES = 80_000;
  private static final int MAX_DEPTH = 6;
  private static final Duration STEP_TIMEOUT = Duration.ofMinutes(8);
  private static final Duration BUDGET = Duration.ofMinutes(15);

  private final LocalToolchain toolchain;
  private final Confinement confinement;

  DependencyResolver(LocalToolchain toolchain, Confinement confinement) {
    this.toolchain = toolchain;
    this.confinement = confinement;
  }

  /** Copies the project without VCS data and build output; false when it is too large to copy. */
  boolean copy(Path source, Path destination) throws IOException {
    long[] total = {0, 0};
    boolean[] tooLarge = {false};
    Files.walkFileTree(source, new SimpleFileVisitor<>() {
      @Override
      public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes)
          throws IOException {
        if (!dir.equals(source) && SKIP_DIRECTORIES.contains(dir.getFileName().toString())) {
          return FileVisitResult.SKIP_SUBTREE;
        }
        Files.createDirectories(destination.resolve(source.relativize(dir).toString()));
        return FileVisitResult.CONTINUE;
      }

      @Override
      public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
        if (!attributes.isRegularFile()) return FileVisitResult.CONTINUE;
        total[0] += attributes.size();
        total[1]++;
        if (total[0] > MAX_BYTES || total[1] > MAX_FILES) {
          tooLarge[0] = true;
          return FileVisitResult.TERMINATE;
        }
        Files.copy(file, destination.resolve(source.relativize(file).toString()),
            StandardCopyOption.COPY_ATTRIBUTES);
        return FileVisitResult.CONTINUE;
      }
    });
    return !tooLarge[0];
  }

  /** Runs the resolver of every package manager found under {@code root}. */
  List<Resolution> resolve(Path root, Path cache, Path home) {
    List<Resolution> steps = new ArrayList<>();
    long deadline = System.nanoTime() + BUDGET.toNanos();
    List<Path> directories = new ArrayList<>();
    try {
      collect(root, root, 0, directories);
    } catch (IOException exception) {
      return List.of(new Resolution("project", ".", FAILED,
          "The project could not be inspected: " + exception.getMessage()));
    }
    for (Path directory : directories) {
      String label = root.relativize(directory).toString();
      if (label.isEmpty()) label = ".";
      if (has(directory, "pom.xml") && !ancestorHas(root, directory, "pom.xml")) {
        steps.add(run("maven", label, directory, root, cache, home, deadline, maven(directory, cache)));
      }
      if ((has(directory, "build.gradle") || has(directory, "build.gradle.kts")
          || has(directory, "settings.gradle") || has(directory, "settings.gradle.kts"))
          && !ancestorHas(root, directory, "build.gradle", "build.gradle.kts", "settings.gradle",
              "settings.gradle.kts")) {
        steps.add(run("gradle", label, directory, root, cache, home, deadline,
            gradle(directory, home, cache)));
      }
      if (has(directory, "package.json")) {
        if (hasAny(directory, "package-lock.json", "npm-shrinkwrap.json", "yarn.lock",
            "pnpm-lock.yaml", "bun.lock", "bun.lockb")
            || ancestorHas(root, directory, "package-lock.json", "yarn.lock", "pnpm-lock.yaml")) {
          steps.add(new Resolution("npm", label, NOT_NEEDED, "a lockfile is present"));
        } else {
          steps.add(run("npm", label, directory, root, cache, home, deadline, npm(directory, cache)));
        }
      }
      if (has(directory, "Cargo.toml") && !hasAny(directory, "Cargo.lock")
          && !ancestorHas(root, directory, "Cargo.lock")) {
        steps.add(run("cargo", label, directory, root, cache, home, deadline,
            tool("cargo", directory, null, List.of("cargo", "generate-lockfile"), cache, "cargo")));
      }
      if (has(directory, "composer.json") && !hasAny(directory, "composer.lock")) {
        steps.add(run("composer", label, directory, root, cache, home, deadline,
            tool("composer", directory, null, List.of("composer", "update", "--no-install",
                "--no-scripts", "--no-plugins", "--no-interaction", "--no-progress"), cache,
                "composer")));
      }
      if (has(directory, "Gemfile") && !hasAny(directory, "Gemfile.lock")) {
        steps.add(run("bundler", label, directory, root, cache, home, deadline,
            tool("bundle", directory, null, List.of("bundle", "lock"), cache, "bundler")));
      }
      if (has(directory, "go.mod")) {
        steps.add(new Resolution("go", label, NOT_NEEDED, "go.mod lists the module graph"));
      }
      if ((has(directory, "pyproject.toml") || has(directory, "requirements.txt"))
          && !hasAny(directory, "poetry.lock", "uv.lock", "Pipfile.lock", "pdm.lock")) {
        steps.add(new Resolution("python", label, SKIPPED,
            "no lockfile: only the declared version constraints are checked, not what pip would "
                + "install"));
      }
    }
    return steps;
  }

  // --- commands ---------------------------------------------------------------------------------

  private Prepared maven(Path directory, Path cache) {
    Path repository = cache.resolve("maven");
    List<String> tail = List.of("--batch-mode", "--no-transfer-progress", "-q", "dependency:resolve",
        "-Dmaven.repo.local=" + repository);
    List<String> command = withWrapper(directory, "mvnw", "mvn", tail);
    Map<String, String> env = new LinkedHashMap<>();
    env.put("MAVEN_OPTS", "-Dmaven.repo.local=" + repository);
    return new Prepared("maven", command, env,
        "no mvnw in the project and no mvn installed; install Maven with install_packages");
  }

  private Prepared gradle(Path directory, Path home, Path cache) {
    // Gradle only writes a lockfile for configurations that opt in; an init script opts every
    // project of the build in, and the lockfile is what the scanner reads.
    Path script = home.resolve("petri-lock.init.gradle");
    try {
      Files.writeString(script, "allprojects {\n  dependencyLocking { lockAllConfigurations() }\n}\n");
    } catch (IOException exception) {
      return new Prepared("gradle", null, Map.of(), "could not prepare the Gradle init script");
    }
    List<String> tail = List.of("--no-daemon", "--quiet", "--init-script", script.toString(),
        "dependencies", "--write-locks");
    List<String> command = withWrapper(directory, "gradlew", "gradle", tail);
    // One Gradle home per project cache, shared with test runs; configured remotes are placed in it.
    return new Prepared("gradle", command, Map.of("GRADLE_USER_HOME", cache.resolve("gradle").toString()),
        "no gradlew in the project and no gradle installed; install Gradle with install_packages");
  }

  private Prepared npm(Path directory, Path cache) {
    Map<String, String> env = new LinkedHashMap<>();
    env.put("npm_config_cache", cache.resolve("npm").toString());
    env.put("npm_config_update_notifier", "false");
    return tool("npm", directory, env,
        List.of("npm", "install", "--package-lock-only", "--ignore-scripts", "--no-audit",
            "--no-fund", "--no-progress"), cache, "npm");
  }

  private Prepared tool(
      String executable, Path directory, Map<String, String> env, List<String> command,
      Path cache, String ecosystem) {
    Map<String, String> values = env == null ? new LinkedHashMap<>() : new LinkedHashMap<>(env);
    if ("cargo".equals(ecosystem)) values.put("CARGO_HOME", cache.resolve("cargo").toString());
    if ("composer".equals(ecosystem)) values.put("COMPOSER_HOME", cache.resolve("composer").toString());
    boolean available = available(executable);
    return new Prepared(ecosystem, available ? command : null, values,
        executable + " is not installed in this Petri instance; install it with install_packages");
  }

  private List<String> withWrapper(Path directory, String wrapper, String tool, List<String> tail) {
    List<String> command = new ArrayList<>();
    if (Files.isExecutable(directory.resolve(wrapper))) command.add("./" + wrapper);
    else if (available(tool)) command.add(tool);
    else return null;
    command.addAll(tail);
    return command;
  }

  private boolean available(String tool) {
    if (toolchain.hasTool(tool)) return true;
    for (String directory : System.getenv().getOrDefault("PATH", "").split(":")) {
      if (directory.isBlank()) continue;
      Path candidate = Path.of(directory).resolve(tool);
      if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return true;
    }
    return false;
  }

  private Resolution run(
      String ecosystem, String label, Path directory, Path root, Path cache, Path home,
      long deadline, Prepared prepared) {
    if (prepared.command() == null) {
      return new Resolution(ecosystem, label, TOOL_MISSING, prepared.missing());
    }
    if (System.nanoTime() >= deadline) {
      return new Resolution(ecosystem, label, SKIPPED, "the time budget for resolution was used up");
    }
    try {
      List<String> argv = confinement.available()
          ? confinement.wrap(List.of(), List.of(root, cache, home), prepared.command())
          : prepared.command();
      Map<String, String> environment = toolchain.environment(home, prepared.environment());
      TestCommandResult result = BoundedProcess.run(
          argv, directory, environment, STEP_TIMEOUT, 16 * 1024, ecosystem + "-resolve");
      if (result.timedOut()) {
        return new Resolution(ecosystem, label, FAILED,
            "did not finish within " + STEP_TIMEOUT.toMinutes() + " minutes");
      }
      if (result.exitCode() != 0) {
        return new Resolution(ecosystem, label, FAILED,
            "exit " + result.exitCode() + ": " + tail(result.output(), 400));
      }
      return new Resolution(ecosystem, label, RESOLVED, "dependencies resolved");
    } catch (IOException | RuntimeException exception) {
      return new Resolution(ecosystem, label, FAILED, "could not run: " + exception.getMessage());
    }
  }

  // --- project inspection -----------------------------------------------------------------------

  private void collect(Path root, Path directory, int depth, List<Path> result) throws IOException {
    result.add(directory);
    if (depth >= MAX_DEPTH) return;
    try (var children = Files.newDirectoryStream(directory, Files::isDirectory)) {
      List<Path> sorted = new ArrayList<>();
      children.forEach(sorted::add);
      sorted.sort(java.util.Comparator.naturalOrder());
      for (Path child : sorted) {
        if (!SKIP_DIRECTORIES.contains(child.getFileName().toString())) {
          collect(root, child, depth + 1, result);
        }
      }
    }
  }

  private static boolean has(Path directory, String name) {
    return Files.isRegularFile(directory.resolve(name));
  }

  private static boolean hasAny(Path directory, String... names) {
    for (String name : names) if (has(directory, name)) return true;
    return false;
  }

  /** Whether a parent directory below the scanned root already carries one of these files. */
  private static boolean ancestorHas(Path root, Path directory, String... names) {
    Path parent = directory.getParent();
    while (parent != null && parent.startsWith(root) && !parent.equals(directory)) {
      if (hasAny(parent, names)) return true;
      if (parent.equals(root)) break;
      parent = parent.getParent();
    }
    return false;
  }

  private static String tail(String value, int max) {
    if (value == null) return "";
    String trimmed = value.strip();
    return trimmed.length() <= max ? trimmed : "…" + trimmed.substring(trimmed.length() - max);
  }

  private record Prepared(
      String ecosystem, List<String> command, Map<String, String> environment, String missing) {}
}
