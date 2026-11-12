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
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import tech.wenisch.petri.agent.run.TrivyScanService.Resolution;
import tech.wenisch.petri.packages.PackageEnvironment;
import tech.wenisch.petri.workspace.WorkspaceHost;
import tech.wenisch.petri.workspace.WorkspaceLayout;

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

  static final Set<String> SKIP_DIRECTORIES = Set.of(
      ".git", "node_modules", "target", "build", ".gradle", ".idea", ".venv", "venv",
      "__pycache__", "dist", "out", ".next", ".cache");
  static final long MAX_BYTES = 500L * 1024 * 1024;
  static final int MAX_FILES = 80_000;
  private static final int MAX_DEPTH = 6;
  private static final Duration STEP_TIMEOUT = Duration.ofMinutes(8);
  private static final Duration BUDGET = Duration.ofMinutes(15);

  private final LocalToolchain toolchain;
  private final Confinement confinement;
  private final CommandEnvironment commandEnvironment;
  private final TestSandboxProfileService profiles;
  private final PackageEnvironment packages;

  @Autowired
  DependencyResolver(
      LocalToolchain toolchain, Confinement confinement, CommandEnvironment commandEnvironment,
      TestSandboxProfileService profiles, ObjectProvider<PackageEnvironment> packages) {
    this.toolchain = toolchain;
    this.confinement = confinement;
    this.commandEnvironment = commandEnvironment;
    this.profiles = profiles;
    this.packages = packages.getIfAvailable();
  }

  DependencyResolver(LocalToolchain toolchain, Confinement confinement) {
    this.toolchain = toolchain;
    this.confinement = confinement;
    this.commandEnvironment = CommandEnvironment.none();
    this.profiles = null;
    this.packages = null;
  }

  /** Where a resolution step runs: in Petri's own container, or in the project's Kubernetes pod. */
  interface StepRunner {
    /** Whether the tool can be started. */
    boolean hasTool(String tool);

    /** Whether the project's wrapper script (mvnw, gradlew) can be started. */
    boolean executable(Path directory, String wrapper);

    /** Writes a small file where the step's tool can read it (a path as the runner sees it). */
    void writeFile(Path file, String content) throws IOException;

    TestCommandResult run(String ecosystem, Path directory, List<String> command,
        Map<String, String> environment, Duration timeout) throws IOException;
  }

  /** Steps run in Petri's container under the same confinement as test runs. */
  private final class LocalRunner implements StepRunner {
    private final Path root;
    private final Path cache;
    private final Path home;

    private LocalRunner(Path root, Path cache, Path home) {
      this.root = root;
      this.cache = cache;
      this.home = home;
    }

    @Override public boolean hasTool(String tool) { return available(tool); }

    @Override public boolean executable(Path directory, String wrapper) {
      return Files.isExecutable(directory.resolve(wrapper));
    }

    @Override public void writeFile(Path file, String content) throws IOException {
      Files.writeString(file, content);
    }

    @Override
    public TestCommandResult run(String ecosystem, Path directory, List<String> command,
        Map<String, String> environment, Duration timeout) throws IOException {
      List<String> argv = confinement.available()
          ? confinement.wrap(List.of(), List.of(root, cache, home), command)
          : command;
      return BoundedProcess.run(argv, directory, toolchain.environment(home, environment), timeout,
          16 * 1024, ecosystem + "-resolve");
    }
  }

  /** Steps run in the pod, on a copy of the project that lives in the pod's scratch space. */
  private final class PodRunner implements StepRunner {
    private final WorkspaceHost host;
    private final UUID projectId;
    private final Path localRoot;
    private final Path podRoot;
    private final Path podHome;
    private final Path podCache;
    private Map<String, String> base;

    private PodRunner(WorkspaceHost host, UUID projectId, Path localRoot, Path podRoot, Path podHome,
        Path podCache) {
      this.host = host;
      this.projectId = projectId;
      this.localRoot = localRoot;
      this.podRoot = podRoot;
      this.podHome = podHome;
      this.podCache = podCache;
    }

    @Override public boolean hasTool(String tool) { return host.processes().hasTool(tool); }

    @Override public boolean executable(Path directory, String wrapper) {
      try {
        return host.layout().stat(podPath(directory).resolve(wrapper)).executable();
      } catch (IOException exception) {
        return false;
      }
    }

    @Override public void writeFile(Path file, String content) throws IOException {
      Path staging = Files.createTempDirectory("petri-pod-file-");
      try {
        Files.writeString(staging.resolve(file.getFileName().toString()), content);
        host.archives().uploadTree(staging, file.getParent());
      } finally {
        deleteQuietly(staging);
      }
    }

    private Path podPath(Path local) {
      return podRoot.resolve(localRoot.relativize(local).toString()).normalize();
    }

    /** The environment every step starts from: as in a terminal command of the pod. */
    private Map<String, String> base() throws IOException {
      if (base != null) return base;
      Map<String, String> env = new LinkedHashMap<>();
      env.put("HOME", podHome.toString());
      env.put("CI", "true");
      env.put("LC_ALL", "C.UTF-8");
      env.putAll(commandEnvironment.extra(profiles == null ? Map.of() : profiles.environmentFor(projectId)));
      if (packages != null) env.putAll(packages.remote(projectId, host, podHome, podCache));
      env.putAll(LocalToolchain.temporaryDirectoryEnvironment(podHome));
      base = env;
      return env;
    }

    @Override
    public TestCommandResult run(String ecosystem, Path directory, List<String> command,
        Map<String, String> environment, Duration timeout) throws IOException {
      Map<String, String> env = new LinkedHashMap<>(base());
      env.putAll(environment);
      return host.processes().exec(podPath(directory), command, env, timeout, 16 * 1024,
          "kubernetes-pod", null);
    }
  }

  private static void deleteQuietly(Path directory) {
    try (var walk = Files.walk(directory)) {
      walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
    } catch (IOException ignored) {
      // scratch space
    }
  }

  /**
   * Resolves the project's dependencies inside its Kubernetes pod, where the project's own tools and
   * package sources are, and brings the produced lockfiles back. {@code localCopy} is a copy of
   * {@code podTarget} in Petri that the scan reads; it is updated with what the tools wrote.
   */
  List<Resolution> resolveInPod(WorkspaceHost host, Path podTarget, Path localCopy) {
    UUID projectId = host.projectId();
    WorkspaceLayout layout = host.layout();
    Path work = null;
    try {
      work = layout.createTemporaryDirectory("petri-resolve-");
      Path podProject = work.resolve("project");
      if (!host.files().copyTree(podTarget, podProject, SKIP_DIRECTORIES, MAX_BYTES, MAX_FILES)) {
        return List.of(new Resolution("project", ".", SKIPPED,
            "the project is too large to copy, so dependencies were not resolved and only committed "
                + "lockfiles were read"));
      }
      Path cache = layout.testCaches(projectId);
      PodRunner runner = new PodRunner(host, projectId, localCopy, podProject, work, cache);
      List<Resolution> steps = resolve(localCopy, cache, work, runner);
      if (steps.stream().anyMatch(step -> RESOLVED.equals(step.status()))) {
        // The tools wrote lockfiles (and the like) into the pod's copy: bring those back.
        host.archives().downloadTree(podProject, localCopy, SKIP_DIRECTORIES, MAX_BYTES, MAX_FILES);
      }
      return steps;
    } catch (IOException | RuntimeException exception) {
      return List.of(new Resolution("project", ".", FAILED,
          "dependencies could not be resolved in the pod: " + exception.getMessage()));
    } finally {
      if (work != null) layout.deleteTemporary(work);
    }
  }

  /** Runs the resolver of every package manager found under {@code root}. */
  List<Resolution> resolve(Path root, Path cache, Path home) {
    return resolve(root, cache, home, new LocalRunner(root, cache, home));
  }

  List<Resolution> resolve(Path root, Path cache, Path home, StepRunner runner) {
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
        steps.add(run(runner, "maven", label, directory, deadline, maven(runner, directory, cache)));
      }
      if ((has(directory, "build.gradle") || has(directory, "build.gradle.kts")
          || has(directory, "settings.gradle") || has(directory, "settings.gradle.kts"))
          && !ancestorHas(root, directory, "build.gradle", "build.gradle.kts", "settings.gradle",
              "settings.gradle.kts")) {
        steps.add(run(runner, "gradle", label, directory, deadline,
            gradle(runner, directory, home, cache)));
      }
      if (has(directory, "package.json")) {
        if (hasAny(directory, "package-lock.json", "npm-shrinkwrap.json", "yarn.lock",
            "pnpm-lock.yaml", "bun.lock", "bun.lockb")
            || ancestorHas(root, directory, "package-lock.json", "yarn.lock", "pnpm-lock.yaml")) {
          steps.add(new Resolution("npm", label, NOT_NEEDED, "a lockfile is present"));
        } else {
          steps.add(run(runner, "npm", label, directory, deadline, npm(runner, directory, cache)));
        }
      }
      if (has(directory, "Cargo.toml") && !hasAny(directory, "Cargo.lock")
          && !ancestorHas(root, directory, "Cargo.lock")) {
        steps.add(run(runner, "cargo", label, directory, deadline,
            tool(runner, "cargo", directory, null, List.of("cargo", "generate-lockfile"), cache, "cargo")));
      }
      if (has(directory, "composer.json") && !hasAny(directory, "composer.lock")) {
        steps.add(run(runner, "composer", label, directory, deadline,
            tool(runner, "composer", directory, null, List.of("composer", "update", "--no-install",
                "--no-scripts", "--no-plugins", "--no-interaction", "--no-progress"), cache,
                "composer")));
      }
      if (has(directory, "Gemfile") && !hasAny(directory, "Gemfile.lock")) {
        steps.add(run(runner, "bundler", label, directory, deadline,
            tool(runner, "bundle", directory, null, List.of("bundle", "lock"), cache, "bundler")));
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

  private Prepared maven(StepRunner runner, Path directory, Path cache) {
    Path repository = cache.resolve("maven");
    List<String> tail = List.of("--batch-mode", "--no-transfer-progress", "-q", "dependency:resolve",
        "-Dmaven.repo.local=" + repository);
    List<String> command = withWrapper(runner, directory, "mvnw", "mvn", tail);
    Map<String, String> env = new LinkedHashMap<>();
    env.put("MAVEN_OPTS", "-Dmaven.repo.local=" + repository);
    return new Prepared("maven", command, env,
        "no mvnw in the project and no mvn installed; install Maven with install_packages");
  }

  private Prepared gradle(StepRunner runner, Path directory, Path home, Path cache) {
    // Gradle only writes a lockfile for configurations that opt in; an init script opts every
    // project of the build in, and the lockfile is what the scanner reads.
    Path script = home.resolve("petri-lock.init.gradle");
    try {
      runner.writeFile(script, "allprojects {\n  dependencyLocking { lockAllConfigurations() }\n}\n");
    } catch (IOException exception) {
      return new Prepared("gradle", null, Map.of(), "could not prepare the Gradle init script");
    }
    List<String> tail = List.of("--no-daemon", "--quiet", "--init-script", script.toString(),
        "dependencies", "--write-locks");
    List<String> command = withWrapper(runner, directory, "gradlew", "gradle", tail);
    // One Gradle home per project cache, shared with test runs; configured remotes are placed in it.
    return new Prepared("gradle", command, Map.of("GRADLE_USER_HOME", cache.resolve("gradle").toString()),
        "no gradlew in the project and no gradle installed; install Gradle with install_packages");
  }

  private Prepared npm(StepRunner runner, Path directory, Path cache) {
    Map<String, String> env = new LinkedHashMap<>();
    env.put("npm_config_cache", cache.resolve("npm").toString());
    env.put("npm_config_update_notifier", "false");
    return tool(runner, "npm", directory, env,
        List.of("npm", "install", "--package-lock-only", "--ignore-scripts", "--no-audit",
            "--no-fund", "--no-progress"), cache, "npm");
  }

  private Prepared tool(
      StepRunner runner, String executable, Path directory, Map<String, String> env, List<String> command,
      Path cache, String ecosystem) {
    Map<String, String> values = env == null ? new LinkedHashMap<>() : new LinkedHashMap<>(env);
    if ("cargo".equals(ecosystem)) values.put("CARGO_HOME", cache.resolve("cargo").toString());
    if ("composer".equals(ecosystem)) values.put("COMPOSER_HOME", cache.resolve("composer").toString());
    boolean available = runner.hasTool(executable);
    return new Prepared(ecosystem, available ? command : null, values,
        executable + " is not installed in this Petri instance; install it with install_packages");
  }

  private List<String> withWrapper(
      StepRunner runner, Path directory, String wrapper, String tool, List<String> tail) {
    List<String> command = new ArrayList<>();
    if (runner.executable(directory, wrapper)) command.add("./" + wrapper);
    else if (runner.hasTool(tool)) command.add(tool);
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
      StepRunner runner, String ecosystem, String label, Path directory, long deadline,
      Prepared prepared) {
    if (prepared.command() == null) {
      return new Resolution(ecosystem, label, TOOL_MISSING, prepared.missing());
    }
    if (System.nanoTime() >= deadline) {
      return new Resolution(ecosystem, label, SKIPPED, "the time budget for resolution was used up");
    }
    try {
      TestCommandResult result =
          runner.run(ecosystem, directory, prepared.command(), prepared.environment(), STEP_TIMEOUT);
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
