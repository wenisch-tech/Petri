package tech.wenisch.petri.agent.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.wenisch.petri.config.PetriProperties;

class TestCommandRunnerTest {
  @TempDir Path temporaryDirectory;

  @Test
  void boundsCombinedOutput() throws Exception {
    Path wrapper = temporaryDirectory.resolve("mvnw");
    executable(wrapper,
        "#!/bin/sh\ni=0\nwhile [ \"$i\" -lt 3000 ]; do printf x; i=$((i+1)); done\n");

    TestCommandResult result = runner(Duration.ofSeconds(5), 1024).run(
        temporaryDirectory,
        new TestCommand(TestBuildSystem.MAVEN, List.of("./mvnw", "test")));

    assertThat(result.exitCode()).isZero();
    assertThat(result.output()).hasSize(1024);
    assertThat(result.truncated()).isTrue();
    assertThat(result.timedOut()).isFalse();
  }

  @Test
  void terminatesTimedOutCommand() throws Exception {
    Path wrapper = temporaryDirectory.resolve("gradlew");
    executable(wrapper, "#!/bin/sh\nsleep 5\n");

    TestCommandResult result = runner(Duration.ofMillis(100), 1024).run(
        temporaryDirectory,
        new TestCommand(TestBuildSystem.GRADLE, List.of("./gradlew", "test")));

    assertThat(result.timedOut()).isTrue();
    assertThat(result.exitCode()).isEqualTo(-1);
  }

  private TestCommandRunner runner(Duration timeout, int outputLimit) {
    PetriProperties properties = new PetriProperties(
        null,
        null,
        new PetriProperties.Workspace(
            temporaryDirectory.resolve("workspace").toString(),
            null,
            null,
            null,
            null,
            null,
            timeout,
            outputLimit),
        null);
    return new TestCommandRunner(properties);
  }

  private static void executable(Path path, String content) throws Exception {
    Files.writeString(path, content);
    if (!path.toFile().setExecutable(true)) throw new AssertionError("Could not mark script executable");
  }
}
