package tech.wenisch.petri.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.wenisch.petri.config.PetriProperties;

class WorkspacePathsTest {
  @TempDir Path temporaryDirectory;

  @Test
  void resolvesMirrorsAndRunsInsideConfiguredRoot() throws Exception {
    WorkspacePaths paths = paths(temporaryDirectory.resolve("workspace"));
    UUID projectId = UUID.randomUUID();
    UUID runId = UUID.randomUUID();

    assertThat(paths.mirror(projectId))
        .isDirectory()
        .startsWith(paths.root())
        .endsWith(Path.of("mirrors", projectId + ".git"));
    Path worktree = paths.runWorktree(projectId, runId);
    assertThat(worktree).doesNotExist();
    assertThat(worktree.endsWith(Path.of("runs", projectId.toString(), runId.toString()))).isTrue();
    assertThat(worktree.toAbsolutePath().normalize().startsWith(paths.root())).isTrue();
  }

  @Test
  void rejectsTraversalOutsideWorkspace() throws Exception {
    WorkspacePaths paths = paths(temporaryDirectory.resolve("workspace"));
    Path outside = Files.createDirectory(temporaryDirectory.resolve("outside"));

    assertThatIllegalArgumentException().isThrownBy(() -> paths.directory(".."));
    assertThatIllegalArgumentException().isThrownBy(() -> paths.directory("nested/../../outside"));
    assertThatIllegalArgumentException().isThrownBy(() -> paths.requireExistingPath(outside));
  }

  @Test
  void rejectsSymbolicLinksWithinWorkspace() throws Exception {
    Path root = Files.createDirectory(temporaryDirectory.resolve("workspace"));
    Path outside = Files.createDirectory(temporaryDirectory.resolve("outside"));
    Files.createSymbolicLink(root.resolve("mirrors"), outside);

    assertThatIllegalStateException()
        .isThrownBy(() -> paths(root).directory("mirrors", "project.git"));
    assertThat(Files.list(outside)).isEmpty();
  }

  private static WorkspacePaths paths(Path root) {
    return new WorkspacePaths(new PetriProperties(
        null,
        null,
        new PetriProperties.Workspace(
            root.toString(), null, null, null, null, null, null, null),
        null));
  }
}
