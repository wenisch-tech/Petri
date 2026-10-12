package tech.wenisch.petri.agent.run;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tech.wenisch.petri.workspace.WorkspacePaths;

/**
 * Runs approved tests directly inside Petri's own (Alpine) container, using the packages installed
 * into the unprivileged toolchain prefix. Opt-in via {@code petri.tests.executor=local}: unlike the
 * Podman executor this does not isolate repository code from the Petri process.
 */
@Component
@ConditionalOnProperty(name = "petri.tests.executor", havingValue = "local")
class LocalContainerTestExecutor implements TestExecutor {
  private final WorkspacePaths paths;
  private final LocalToolchain toolchain;
  private final Confinement confinement;

  LocalContainerTestExecutor(
      WorkspacePaths paths, LocalToolchain toolchain, Confinement confinement) {
    this.paths = paths;
    this.toolchain = toolchain;
    this.confinement = confinement;
  }

  @Override
  public TestCommandResult run(
      UUID projectId, Path worktree, TestCommand command, TestSandboxProfileView profile)
      throws IOException {
    return run(projectId, worktree, command, profile, null);
  }

  @Override
  public TestCommandResult run(
      UUID projectId, Path worktree, TestCommand command, TestSandboxProfileView profile,
      java.util.function.Consumer<String> progress) throws IOException {
    if (!profile.buildSystems().contains(command.buildSystem())) {
      throw new IllegalArgumentException("Build system is not allowed by the sandbox profile");
    }
    Path safeWorktree = paths.requireExistingPath(worktree);
    Path cache = paths.directory("test-caches", projectId.toString());
    Path home = Files.createTempDirectory(paths.temporaryDirectory(), "petri-test-");
    try {
      Map<String, String> extra = command.buildSystem() == TestBuildSystem.MAVEN
          ? Map.of("MAVEN_OPTS", "-Dmaven.repo.local=" + cache.resolve("maven"))
          : Map.of("GRADLE_USER_HOME", cache.resolve("gradle").toString());
      List<String> argv = confinement.available()
          ? confinement.wrap(List.of(safeWorktree, cache, home), command.arguments())
          : command.arguments();
      return BoundedProcess.run(argv, safeWorktree,
          toolchain.environment(home, extra, profile.environment()), profile.timeout(), profile.outputLimitBytes(),
          confinement.available() ? "local-container-landlock" : "local-container", progress);
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
}
