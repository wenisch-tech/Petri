package tech.wenisch.petri.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * A worktree path obtained from a {@link WorkspaceHost} may live in a Kubernetes pod, so only the
 * local host implementations and Petri's own local state (toolchain, package configuration, secrets,
 * skills) may touch the filesystem with {@code java.nio.file.Files}. Everything else must go
 * through the host seam.
 */
class WorkspaceHostBoundaryTest {
  private static final Path SOURCES = Path.of("src/main/java/tech/wenisch/petri");

  /** Local-only code: it either implements the local host or handles Petri's own data volume. */
  private static final Set<String> ALLOWED = Set.of(
      "agent/run/Confinement.java",
      "agent/run/DependencyResolver.java",
      "agent/run/LocalContainerTestExecutor.java",
      "agent/run/LocalToolchain.java",
      "agent/run/PackageInstallService.java",
      "agent/run/TerminalRunner.java",
      "agent/run/TestCommandRunner.java",
      "agent/run/TrivyScanService.java",
      "agent/skill/SkillService.java",
      "agent/tool/WorkspaceFileService.java",
      "git/GitAskPassSession.java",
      "kubernetes/AgentBinaries.java",
      "kubernetes/ToolchainBundles.java",
      "kubernetes/RemoteWorkspaceHost.java",
      "kubernetes/Tar.java",
      "secret/MasterKeyProvider.java",
      "workspace/LocalWorkspaceArchives.java",
      "workspace/WorkspacePaths.java");

  @Test
  void onlyLocalImplementationsUseTheFilesApi() throws IOException {
    List<String> offenders;
    try (Stream<Path> files = Files.walk(SOURCES)) {
      offenders = files.filter(path -> path.toString().endsWith(".java"))
          .map(path -> SOURCES.relativize(path).toString().replace('\\', '/'))
          .filter(relative -> !relative.startsWith("packages/"))
          .filter(relative -> !ALLOWED.contains(relative))
          .filter(WorkspaceHostBoundaryTest::usesFiles)
          .sorted().toList();
    }
    assertThat(offenders)
        .as("Use WorkspaceHost (layout, files, processes, git, archives) instead of java.nio.file.Files")
        .isEmpty();
  }

  private static boolean usesFiles(String relative) {
    try {
      return Files.readAllLines(SOURCES.resolve(relative)).stream()
          .filter(line -> !line.strip().startsWith("*") && !line.strip().startsWith("//"))
          .anyMatch(line -> line.contains("import java.nio.file.Files;")
              || line.contains("java.nio.file.Files."));
    } catch (IOException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
