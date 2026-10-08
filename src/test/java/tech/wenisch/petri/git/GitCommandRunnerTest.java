package tech.wenisch.petri.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.wenisch.petri.config.PetriProperties;
import tech.wenisch.petri.workspace.WorkspacePaths;

class GitCommandRunnerTest {
  @TempDir Path temporaryDirectory;

  @Test
  void runsOnlyWithinWorkspaceAndDisablesInteractivePrompts() throws Exception {
    GitCommandRunner runner = runner(Duration.ofSeconds(5), 4096);
    Path workspace = workspace();

    GitCommandResult result = runner.run(workspace, List.of("--version"));

    assertThat(result.succeeded()).isTrue();
    assertThat(result.output()).contains("git version");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> runner.run(temporaryDirectory.getParent(), List.of("--version")));
  }

  @Test
  void timesOutACommandWaitingForInput() throws Exception {
    GitCommandRunner runner = runner(Duration.ofMillis(100), 4096);

    GitCommandResult result = runner.run(workspace(), List.of("hash-object", "--stdin"));

    assertThat(result.timedOut()).isTrue();
    assertThat(result.succeeded()).isFalse();
  }

  @Test
  void boundsOutputAndRedactsEnvironmentSuppliedSecrets() throws Exception {
    GitCommandRunner runner = runner(Duration.ofSeconds(5), 1024);
    Path workspace = workspace();
    Path before = workspace.resolve("before.txt");
    Path after = workspace.resolve("after.txt");
    Files.writeString(before, "a\n".repeat(3000));
    Files.writeString(after, "b\n".repeat(3000));

    GitCommandResult large = runner.run(
        workspace, List.of("diff", "--no-index", "--", before.toString(), after.toString()));
    assertThat(large.truncated()).isTrue();
    assertThat(large.output().getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
        .isLessThanOrEqualTo(1024);

    GitCommandResult redacted = runner.run(
        workspace,
        List.of("leak"),
        Map.of(
            "GIT_CONFIG_COUNT", "1",
            "GIT_CONFIG_KEY_0", "alias.leak",
            "GIT_CONFIG_VALUE_0", "!printf hidden-secret"),
        List.of("hidden-secret"));
    assertThat(redacted.output()).contains("********").doesNotContain("hidden-secret");
  }

  @Test
  void refusesSecretsInCommandArguments() throws Exception {
    GitCommandRunner runner = runner(Duration.ofSeconds(5), 4096);

    assertThatIllegalArgumentException().isThrownBy(() -> runner.run(
        workspace(),
        List.of("ls-remote", "https://user:hidden-secret@example.test/repository.git"),
        Map.of(),
        List.of("hidden-secret")));
  }

  private GitCommandRunner runner(Duration timeout, int outputLimit) {
    PetriProperties properties = new PetriProperties(
        null,
        null,
        new PetriProperties.Workspace(
            temporaryDirectory.resolve("workspace").toString(), timeout, outputLimit));
    return new GitCommandRunner(new WorkspacePaths(properties), properties);
  }

  private Path workspace() throws Exception {
    return Files.createDirectories(temporaryDirectory.resolve("workspace")).toRealPath();
  }
}
