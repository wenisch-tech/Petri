package tech.wenisch.petri.agent.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.wenisch.petri.config.PetriProperties;
import tech.wenisch.petri.workspace.WorkspacePaths;

class TestCommandRunnerTest {
  @TempDir Path temporaryDirectory;

  @Test
  void buildsImmutableRootlessPodmanCommand() throws Exception {
    WorkspacePaths paths = paths();
    Path worktree = paths.directory("runs", "worktree");
    Path cache = paths.directory("test-caches", "project");
    TestCommandRunner runner = new TestCommandRunner(paths, "podman");

    List<String> command = runner.containerArguments("petri-test-id", worktree, cache,
        new TestCommand(TestBuildSystem.MAVEN, List.of("./mvnw", "test")), profile(false));

    assertThat(command).containsSubsequence("podman", "run", "--rm", "--name", "petri-test-id");
    assertThat(command).contains("--pull=missing", "--userns=keep-id", "--read-only",
        "--cap-drop=all", "--security-opt=no-new-privileges", "--network=none",
        "--pids-limit=256", "--cpus=2", "--memory=2g",
        "HOME=/tmp/petri-home", "MAVEN_OPTS=-Dmaven.repo.local=/cache/maven");
    assertThat(command).contains(worktree + ":/workspace:rw,Z", cache + ":/cache:rw,Z");
    assertThat(command).endsWith("example/java:25", "./mvnw", "test");
    assertThat(command).noneMatch(value -> value.contains(System.getProperty("user.home")));
  }

  @Test
  void passesConfiguredEnvironmentIntoTheContainer() throws Exception {
    WorkspacePaths paths = paths();
    Path worktree = paths.directory("runs", "worktree");
    Path cache = paths.directory("test-caches", "project");
    CommandEnvironment instance = new CommandEnvironment(java.util.Map.of(
        "HTTPS_PROXY", "http://proxy.test:3128", "SECRET_TOKEN", "must-not-leak"));
    TestCommandRunner runner = new TestCommandRunner(paths, "podman", instance);
    TestSandboxProfileView withVariables = new TestSandboxProfileView(UUID.randomUUID(), "Java 25",
        "example/java:25", Set.of(TestBuildSystem.MAVEN), "2", "2g", 256, Duration.ofSeconds(5),
        1024, true, true, true, java.util.Map.of("NO_PROXY", "internal"));

    List<String> command = runner.containerArguments("petri-test-id", worktree, cache,
        new TestCommand(TestBuildSystem.MAVEN, List.of("./mvnw", "test")), withVariables);

    assertThat(command).contains("HTTPS_PROXY=http://proxy.test:3128", "NO_PROXY=internal",
        "https_proxy=http://proxy.test:3128");
    assertThat(command).noneMatch(value -> value.contains("must-not-leak"));
    assertThat(command).endsWith("example/java:25", "./mvnw", "test");
  }

  @Test
  void failsClosedWhenPodmanIsUnavailable() throws Exception {
    WorkspacePaths paths = paths();
    Path worktree = paths.directory("runs", "worktree");
    TestCommandRunner runner = new TestCommandRunner(paths, "petri-missing-podman-runtime");

    assertThatThrownBy(() -> runner.run(UUID.randomUUID(), worktree,
        new TestCommand(TestBuildSystem.GRADLE, List.of("./gradlew", "test")), profile(true)))
        .isInstanceOf(java.io.IOException.class)
        .hasMessageContaining("require rootless Podman");
  }

  private WorkspacePaths paths() {
    return new WorkspacePaths(new PetriProperties(null, null,
        new PetriProperties.Workspace(temporaryDirectory.resolve("workspace").toString(),
            null, null, null, null, null, null, null), null));
  }

  private static TestSandboxProfileView profile(boolean network) {
    return new TestSandboxProfileView(UUID.randomUUID(), "Java 25", "example/java:25",
        Set.of(TestBuildSystem.MAVEN, TestBuildSystem.GRADLE), "2", "2g", 256,
        Duration.ofSeconds(5), 1024, network, true, true, java.util.Map.of());
  }
}
