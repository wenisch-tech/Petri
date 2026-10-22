package tech.wenisch.petri.agent.run;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import tech.wenisch.petri.workspace.WorkspacePaths;

/** Executes approved repository code exclusively in a rootless Podman sandbox. */
@Component
@ConditionalOnProperty(name = "petri.tests.executor", havingValue = "podman", matchIfMissing = true)
class TestCommandRunner implements TestExecutor {
  private final WorkspacePaths paths;
  private final String podmanExecutable;
  private final CommandEnvironment commandEnvironment;

  private final tech.wenisch.petri.packages.PackageEnvironment packages;

  @Autowired
  TestCommandRunner(
      WorkspacePaths paths,
      @Value("${petri.tests.podman-executable:podman}") String podmanExecutable,
      CommandEnvironment commandEnvironment,
      org.springframework.beans.factory.ObjectProvider<tech.wenisch.petri.packages.PackageEnvironment>
          packages) {
    this(paths, podmanExecutable, commandEnvironment, packages.getIfAvailable());
  }

  TestCommandRunner(
      WorkspacePaths paths, String podmanExecutable, CommandEnvironment commandEnvironment,
      tech.wenisch.petri.packages.PackageEnvironment packages) {
    this.packages = packages;
    this.paths = paths;
    this.podmanExecutable = podmanExecutable;
    this.commandEnvironment = commandEnvironment;
  }

  TestCommandRunner(
      WorkspacePaths paths, String podmanExecutable, CommandEnvironment commandEnvironment) {
    this(paths, podmanExecutable, commandEnvironment,
        (tech.wenisch.petri.packages.PackageEnvironment) null);
  }

  TestCommandRunner(WorkspacePaths paths, String podmanExecutable) {
    this(paths, podmanExecutable, CommandEnvironment.none(),
        (tech.wenisch.petri.packages.PackageEnvironment) null);
  }

  @Override
  public TestCommandResult run(
      UUID projectId, Path worktree, TestCommand command, TestSandboxProfileView profile)
      throws IOException {
    Path safeWorktree = paths.requireExistingPath(worktree);
    Path cache = paths.directory("test-caches", projectId.toString());
    String containerName = "petri-test-" + UUID.randomUUID();
    Path packageHome = Files.createTempDirectory(paths.temporaryDirectory(), "petri-pkg-");
    try {
      return runContainer(projectId, safeWorktree, cache, containerName, command, profile, packageHome);
    } finally {
      try (var walk = Files.walk(packageHome)) {
        walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
      } catch (IOException ignored) {
        // best effort; only generated configuration
      }
    }
  }

  private TestCommandResult runContainer(
      UUID projectId, Path safeWorktree, Path cache, String containerName, TestCommand command,
      TestSandboxProfileView profile, Path packageHome) throws IOException {
    var packageSettings = packages == null
        ? tech.wenisch.petri.packages.PackageEnvironment.Container.NONE
        : packages.container(projectId, packageHome, cache);
    List<String> arguments = containerArguments(
        containerName, safeWorktree, cache, command, profile, packageSettings);
    ProcessBuilder builder = new ProcessBuilder(arguments).redirectErrorStream(true);
    sanitizeEnvironment(builder.environment());
    builder.environment().putAll(packageSettings.hostEnv());

    Process process;
    try {
      process = builder.start();
    } catch (IOException exception) {
      throw new IOException("Podman is unavailable; approved tests require rootless Podman", exception);
    }
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<CapturedOutput> output = executor.submit(
          () -> capture(process.getInputStream(), profile.outputLimitBytes()));
      boolean finished;
      try {
        finished = process.waitFor(profile.timeout().toMillis(), TimeUnit.MILLISECONDS);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        cleanup(containerName, process);
        throw new IOException("Interrupted while waiting for sandboxed test command", exception);
      }
      if (!finished) cleanup(containerName, process);
      CapturedOutput captured = readOutput(output);
      String digest = inspectDigest(profile.image());
      return new TestCommandResult(finished ? process.exitValue() : -1, captured.value(),
          !finished, captured.truncated(), digest);
    }
  }

  List<String> containerArguments(
      String name, Path worktree, Path cache, TestCommand command, TestSandboxProfileView profile) {
    return containerArguments(name, worktree, cache, command, profile,
        tech.wenisch.petri.packages.PackageEnvironment.Container.NONE);
  }

  List<String> containerArguments(
      String name, Path worktree, Path cache, TestCommand command, TestSandboxProfileView profile,
      tech.wenisch.petri.packages.PackageEnvironment.Container packageSettings) {
    if (!profile.buildSystems().contains(command.buildSystem())) {
      throw new IllegalArgumentException("Build system is not allowed by the sandbox profile");
    }
    List<String> result = new ArrayList<>();
    result.addAll(List.of(podmanExecutable, "run", "--rm", "--name", name,
        "--pull=missing", "--userns=keep-id", "--read-only", "--cap-drop=all",
        "--security-opt=no-new-privileges", "--pids-limit=" + profile.pidsLimit(),
        "--cpus=" + profile.cpuLimit(), "--memory=" + profile.memoryLimit(),
        "--network=" + (profile.networkEnabled() ? "slirp4netns" : "none"),
        "--tmpfs", "/tmp:rw,nosuid,nodev,size=512m",
        "--volume", worktree + ":/workspace:rw,Z",
        "--volume", cache + ":/cache:rw,Z",
        "--workdir", "/workspace", "--env", "CI=true", "--env", "LC_ALL=C.UTF-8",
        "--env", "HOME=/tmp/petri-home"));
    if (command.buildSystem() == TestBuildSystem.MAVEN) {
      result.addAll(List.of("--env", "MAVEN_OPTS=-Dmaven.repo.local=/cache/maven"));
    } else {
      result.addAll(List.of("--env", "GRADLE_USER_HOME=/cache/gradle"));
    }
    // Reserved names (HOME, MAVEN_OPTS, ...) are rejected by CommandEnvironment, so these cannot
    // override the settings above.
    result.addAll(commandEnvironment.containerEnvArguments(profile.environment()));
    result.addAll(packageSettings.volumes());
    packageSettings.containerEnv().forEach((name1, value) -> result.addAll(List.of("--env", name1 + "=" + value)));
    result.add(profile.image());
    result.addAll(command.arguments());
    return List.copyOf(result);
  }

  private String inspectDigest(String image) {
    Process process = null;
    try {
      ProcessBuilder builder = new ProcessBuilder(
          podmanExecutable, "image", "inspect", "--format", "{{.Digest}}", image)
          .redirectErrorStream(true);
      sanitizeEnvironment(builder.environment());
      process = builder.start();
      byte[] output = process.getInputStream().readNBytes(512);
      if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0) return null;
      String value = new String(output, StandardCharsets.UTF_8).strip();
      return value.isBlank() ? null : value;
    } catch (IOException | InterruptedException exception) {
      if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
      return null;
    } finally {
      if (process != null && process.isAlive()) process.destroyForcibly();
    }
  }

  private void cleanup(String name, Process process) {
    runCleanup("kill", name);
    runCleanup("rm", "--force", name);
    process.destroy();
    try {
      if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      process.destroyForcibly();
    }
  }

  private void runCleanup(String... arguments) {
    List<String> command = new ArrayList<>(); command.add(podmanExecutable); command.addAll(List.of(arguments));
    try {
      ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
      sanitizeEnvironment(builder.environment());
      Process cleanup = builder.start();
      cleanup.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
      if (!cleanup.waitFor(10, TimeUnit.SECONDS)) cleanup.destroyForcibly();
    } catch (IOException | InterruptedException exception) {
      if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
    }
  }

  private static CapturedOutput capture(InputStream input, int outputLimit) throws IOException {
    ByteArrayOutputStream retained = new ByteArrayOutputStream(Math.min(outputLimit, 8192));
    byte[] buffer = new byte[8192]; long total = 0; int read;
    while ((read = input.read(buffer)) >= 0) {
      int writable = Math.min(read, Math.max(0, outputLimit - retained.size()));
      if (writable > 0) retained.write(buffer, 0, writable);
      total += read;
    }
    return new CapturedOutput(retained.toString(StandardCharsets.UTF_8), total > outputLimit);
  }

  private static CapturedOutput readOutput(Future<CapturedOutput> output) throws IOException {
    try {
      return output.get(10, TimeUnit.SECONDS);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while reading sandbox output", exception);
    } catch (ExecutionException exception) {
      throw new IOException("Could not read sandbox output", exception.getCause());
    } catch (TimeoutException exception) {
      throw new IOException("Sandbox output stream did not close", exception);
    }
  }

  private static void sanitizeEnvironment(Map<String, String> environment) {
    Map<String, String> retained = new java.util.HashMap<>();
    // These variables are needed by the rootless Podman client itself. They are not passed
    // into the container; the container receives only the explicit --env values above.
    for (String name : List.of(
        "PATH", "HOME", "USER", "LOGNAME", "XDG_RUNTIME_DIR",
        "DBUS_SESSION_BUS_ADDRESS", "TMPDIR")) {
      String value = environment.get(name);
      if (value != null) retained.put(name, value);
    }
    environment.clear();
    environment.putAll(retained);
    environment.put("LC_ALL", "C.UTF-8");
  }

  private record CapturedOutput(String value, boolean truncated) {}
}
