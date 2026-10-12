package tech.wenisch.petri.agent.run;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tech.wenisch.petri.workspace.WorkspacePaths;

/**
 * Runs a terminal command confined to one worktree. Confinement is enforced by the operating
 * system, never by filtering strings:
 * <ul>
 *   <li>{@code podman} executor: a rootless container with only the worktree mounted, no
 *       capabilities, read-only root filesystem and the project's sandbox profile limits.</li>
 *   <li>{@code local} executor: {@code petri-confine} (Landlock) denies every filesystem access
 *       except reading the system and writing the worktree and a private temporary home. Works for
 *       any uid. Without it, commands are refused.</li>
 * </ul>
 */
@Component
class TerminalRunner {
  private final WorkspacePaths paths;
  private final LocalToolchain toolchain;
  private final Confinement confinement;
  private final TestSandboxProfileService profiles;
  private final CommandEnvironment commandEnvironment;
  private final String executor;
  private final String podman;
  private final Duration timeout;
  private final int outputLimit;

  TerminalRunner(
      WorkspacePaths paths, LocalToolchain toolchain, Confinement confinement,
      TestSandboxProfileService profiles, CommandEnvironment commandEnvironment,
      @Value("${petri.tests.executor:podman}") String executor,
      @Value("${petri.tests.podman-executable:podman}") String podman,
      @Value("${petri.terminal.timeout:2m}") Duration timeout,
      @Value("${petri.terminal.max-output-bytes:200000}") int outputLimit) {
    this.paths = paths;
    this.toolchain = toolchain;
    this.confinement = confinement;
    this.profiles = profiles;
    this.commandEnvironment = commandEnvironment;
    this.executor = executor;
    this.podman = podman;
    this.timeout = timeout;
    this.outputLimit = outputLimit;
  }

  /** Whether commands can be run in a confined way in this deployment. */
  boolean available() {
    return !"local".equalsIgnoreCase(executor) || confinement.available();
  }

  String unavailableReason() {
    return "Terminal commands need OS-level confinement: install petri-confine and set "
        + "PETRI_TERMINAL_CONFINE_COMMAND (the Alpine image does), or use the Podman executor.";
  }

  TestCommandResult run(
      UUID projectId, Path worktree, String command, java.util.function.Consumer<String> progress)
      throws IOException {
    Path safeWorktree = paths.requireExistingPath(worktree);
    if ("local".equalsIgnoreCase(executor)) return runLocal(projectId, safeWorktree, command, progress);
    return runPodman(projectId, safeWorktree, command, progress);
  }

  private TestCommandResult runLocal(
      UUID projectId, Path worktree, String command, java.util.function.Consumer<String> progress)
      throws IOException {
    if (!confinement.available()) throw new IllegalStateException(unavailableReason());
    Path home = Files.createTempDirectory(paths.temporaryDirectory(), "petri-term-");
    try {
      // The same per-project build caches as test runs, so dependencies downloaded by a terminal
      // build are reused and the confined shell has somewhere durable to write them.
      Path cache = paths.directory("test-caches", projectId.toString());
      // /tmp itself is not granted to confined commands; TMPDIR points at this worktree's own
      // scratch directory, which persists between commands and is removed with the worktree.
      Path scratch = paths.worktreeTemporaryDirectory(worktree);
      Map<String, String> extra = new java.util.LinkedHashMap<>();
      extra.put("MAVEN_OPTS", "-Dmaven.repo.local=" + cache.resolve("maven"));
      extra.put("GRADLE_USER_HOME", cache.resolve("gradle").toString());
      extra.putAll(LocalToolchain.temporaryDirectoryEnvironment(scratch));
      Map<String, String> env = toolchain.environment(home, extra, profiles.environmentFor(projectId));
      List<String> argv = confinement.wrap(
          List.of(worktree, home, cache, scratch), List.of("/bin/sh", "-c", command));
      return BoundedProcess.run(argv, worktree, env, timeout, outputLimit, "terminal-landlock", progress);
    } finally {
      deleteQuietly(home);
    }
  }

  private static void deleteQuietly(Path directory) {
    try (var walk = Files.walk(directory)) {
      walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
    } catch (IOException ignored) {
      // best effort
    }
  }

  private TestCommandResult runPodman(
      UUID projectId, Path worktree, String command, java.util.function.Consumer<String> progress)
      throws IOException {
    TestSandboxProfileView profile = profiles.resolve(projectId, TestBuildSystem.MAVEN);
    Path cache = paths.directory("test-caches", projectId.toString());
    String name = "petri-term-" + UUID.randomUUID();
    // The worktree's scratch directory is the container's /tmp: private to this worktree and
    // kept between commands.
    Path scratch = paths.worktreeTemporaryDirectory(worktree);
    Files.createDirectories(scratch.resolve("petri-home"));
    List<String> argv = new ArrayList<>(List.of(podman, "run", "--rm", "--name", name,
        "--pull=missing", "--userns=keep-id", "--read-only", "--cap-drop=all",
        "--security-opt=no-new-privileges", "--pids-limit=" + profile.pidsLimit(),
        "--cpus=" + profile.cpuLimit(), "--memory=" + profile.memoryLimit(),
        "--network=" + (profile.networkEnabled() ? "slirp4netns" : "none"),
        "--volume", scratch + ":/tmp:rw,nosuid,nodev,Z",
        "--volume", worktree + ":/workspace:rw,Z", "--volume", cache + ":/cache:rw,Z",
        "--workdir", "/workspace", "--env", "CI=true", "--env", "LC_ALL=C",
        "--env", "HOME=/tmp/petri-home"));
    argv.addAll(commandEnvironment.containerEnvArguments(profile.environment()));
    argv.addAll(List.of(profile.image(), "sh", "-c", command));
    TestCommandResult result = BoundedProcess.run(argv, worktree,
        Map.of("PATH", System.getenv().getOrDefault("PATH", "/usr/bin:/bin"),
            "HOME", System.getenv().getOrDefault("HOME", "/tmp"),
            "XDG_RUNTIME_DIR", System.getenv().getOrDefault("XDG_RUNTIME_DIR", "/tmp")),
        timeout, outputLimit, "podman", progress);
    if (result.timedOut()) {
      try {
        new ProcessBuilder(podman, "rm", "--force", name).start().waitFor();
      } catch (IOException | InterruptedException ignored) {
        Thread.currentThread().interrupt();
      }
    }
    return result;
  }
}
