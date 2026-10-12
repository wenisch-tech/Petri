package tech.wenisch.petri.git;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.wenisch.petri.config.PetriProperties;
import tech.wenisch.petri.workspace.WorkspacePaths;

class BranchDistanceTest {
  @TempDir Path temporary;
  private Path work;
  private GitCommandRunner git;

  @BeforeEach
  void repository() throws Exception {
    PetriProperties properties = new PetriProperties(null, null,
        new PetriProperties.Workspace(temporary.resolve("workspace").toString(),
            null, null, null, null, null, null, null), null);
    WorkspacePaths paths = new WorkspacePaths(properties);
    git = new GitCommandRunner(paths, properties);
    Path remote = temporary.resolve("remote.git");
    // The Git runner only works inside the workspace root.
    work = paths.root().resolve("work");
    exec(temporary, "git", "init", "-q", "--bare", "-b", "main", remote.toString());
    exec(temporary, "git", "clone", "-q", remote.toString(), work.toString());
    exec(work, "git", "config", "user.email", "t@example.test");
    exec(work, "git", "config", "user.name", "T");
    Files.writeString(work.resolve("a.txt"), "1\n");
    exec(work, "git", "add", ".");
    exec(work, "git", "commit", "-q", "-m", "base");
    exec(work, "git", "push", "-q", "origin", "main");
  }

  private void commit(String file) throws Exception {
    Files.writeString(work.resolve(file), file + "\n");
    exec(work, "git", "add", ".");
    exec(work, "git", "commit", "-q", "-m", "add " + file);
  }

  @Test
  void aCommitOnABranchWithoutAnExistingUpstreamCountsAsAhead() throws Exception {
    exec(work, "git", "checkout", "-q", "-b", "feature");
    commit("b.txt");

    // "origin/feature" is configured by name but was never pushed.
    BranchDistance distance = BranchDistance.of(git, work, "origin/feature");

    assertThat(distance).isEqualTo(new BranchDistance(1, 0));
    assertThat(BranchDistance.of(git, work, null)).isEqualTo(new BranchDistance(1, 0));
  }

  @Test
  void anExistingUpstreamGivesAheadAndBehind() throws Exception {
    commit("b.txt");
    commit("c.txt");

    assertThat(BranchDistance.of(git, work, "origin/main")).isEqualTo(new BranchDistance(2, 0));

    exec(work, "git", "push", "-q", "origin", "main");
    assertThat(BranchDistance.of(git, work, "origin/main")).isEqualTo(new BranchDistance(0, 0));
  }

  @Test
  void aPublishedBranchWithNoNewCommitsIsNotAhead() throws Exception {
    exec(work, "git", "checkout", "-q", "-b", "feature");

    assertThat(BranchDistance.of(git, work, "origin/feature")).isEqualTo(new BranchDistance(0, 0));
  }

  private static void exec(Path directory, String... command) throws Exception {
    List<String> arguments = new ArrayList<>(List.of(command));
    Process process = new ProcessBuilder(arguments).directory(directory.toFile())
        .redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes());
    assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
    assertThat(process.exitValue()).as(output).isZero();
  }
}
