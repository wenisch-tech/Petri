package tech.wenisch.petri.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.wenisch.petri.config.PetriProperties;
import tech.wenisch.petri.workspace.WorkspacePaths;

class PullRequestDiffServiceTest {
  @TempDir Path temporary;

  @Test
  void diffsTheFetchedHeadAgainstTheBaseBranch() throws Exception {
    PetriProperties properties = new PetriProperties(null, null,
        new PetriProperties.Workspace(temporary.resolve("workspace").toString(),
            null, null, null, null, null, null, null), null);
    WorkspacePaths paths = new WorkspacePaths(properties);
    UUID projectId = UUID.randomUUID();
    Path mirror = paths.mirror(projectId);
    Path source = temporary.resolve("source");
    Files.createDirectories(source);
    git(source, "init", "-q", "-b", "main");
    git(source, "config", "user.email", "t@example.test");
    git(source, "config", "user.name", "T");
    Files.writeString(source.resolve("A.txt"), "one\n");
    git(source, "add", ".");
    git(source, "commit", "-q", "-m", "base");
    git(source, "checkout", "-q", "-b", "feature");
    Files.writeString(source.resolve("A.txt"), "one\ntwo\n");
    Files.writeString(source.resolve("B.txt"), "new\n");
    git(source, "add", ".");
    git(source, "commit", "-q", "-m", "change");
    String head = git(source, "rev-parse", "HEAD").strip();
    Files.createDirectories(mirror.getParent());
    git(temporary, "clone", "-q", "--bare", source.toString(), mirror.toString());
    git(mirror, "update-ref", "refs/remotes/origin/main", "refs/heads/main");
    git(mirror, "update-ref", "refs/petri/pr/7", head);

    PullRequestDiffService service = new PullRequestDiffService(paths,
        new GitCommandRunner(paths, properties));
    PullRequestDiffService.PullRequestDiff diff = service.diff(projectId, 7, "main", 100_000);

    assertThat(diff.headSha()).isEqualTo(head);
    assertThat(diff.changedFiles()).containsExactlyInAnyOrder("A.txt", "B.txt");
    assertThat(diff.diff()).contains("+two", "+new");
    assertThat(diff.truncated()).isFalse();
    assertThat(service.diff(projectId, 7, "main", 10).truncated()).isTrue();
    assertThatThrownBy(() -> service.diff(projectId, 7, "main..evil", 100))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.diff(projectId, 8, "main", 100))
        .isInstanceOf(GitOperationException.class);
  }

  private static String git(Path directory, String... arguments) throws Exception {
    List<String> command = new java.util.ArrayList<>();
    command.add("git");
    command.addAll(List.of(arguments));
    Process process = new ProcessBuilder(command).directory(directory.toFile())
        .redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes());
    assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
    assertThat(process.exitValue()).as(output).isZero();
    return output;
  }
}
