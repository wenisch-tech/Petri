package tech.wenisch.petri.agent.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.time.Duration;
import tech.wenisch.petri.config.PetriProperties;
import tech.wenisch.petri.workspace.TestWorkspaceHosts;
import tech.wenisch.petri.workspace.WorkspaceHost;
import tech.wenisch.petri.workspace.WorkspacePaths;

class TestCommandPolicyTest {
  @TempDir Path temporaryDirectory;

  private final TestCommandPolicy policy = new TestCommandPolicy();

  private WorkspaceHost host(LocalToolchain toolchain, String executor) {
    return TestWorkspaceHosts.host(
        new WorkspacePaths(new PetriProperties(null, null, new PetriProperties.Workspace(
            temporaryDirectory.resolve("workspace-root").toString(), Duration.ofSeconds(5),
            4096, 1024, 4096, 2, null, null), null)),
        null, new LocalWorkspaceProcesses(toolchain, executor), null, null);
  }

  private WorkspaceHost host() {
    return host(null, "podman");
  }

  @Test
  void permitsOnlyFixedMavenOrGradleWrapperCommands() throws Exception {
    Path maven = Files.createDirectory(temporaryDirectory.resolve("maven"));
    Files.writeString(maven.resolve("pom.xml"), "<project/>");
    executable(maven.resolve("mvnw"), "#!/bin/sh\nexit 0\n");
    TestCommand mavenCommand = policy.detect(host(), maven);
    assertThat(mavenCommand.buildSystem()).isEqualTo(TestBuildSystem.MAVEN);
    assertThat(mavenCommand.arguments()).containsExactly(
        "./mvnw", "--batch-mode", "--no-transfer-progress", "test");

    Path gradle = Files.createDirectory(temporaryDirectory.resolve("gradle"));
    Files.writeString(gradle.resolve("build.gradle.kts"), "plugins {}\n");
    executable(gradle.resolve("gradlew"), "#!/bin/sh\nexit 0\n");
    TestCommand gradleCommand = policy.detect(host(), gradle);
    assertThat(gradleCommand.buildSystem()).isEqualTo(TestBuildSystem.GRADLE);
    assertThat(gradleCommand.arguments()).containsExactly("./gradlew", "--no-daemon", "test");
  }

  @Test
  void rejectsMissingNonExecutableAmbiguousAndSymlinkedWrappers() throws Exception {
    Path missing = Files.createDirectory(temporaryDirectory.resolve("missing"));
    assertThatIllegalArgumentException().isThrownBy(() -> policy.detect(host(), missing));

    Path nonExecutable = Files.createDirectory(temporaryDirectory.resolve("non-executable"));
    Files.writeString(nonExecutable.resolve("pom.xml"), "<project/>");
    Files.writeString(nonExecutable.resolve("mvnw"), "#!/bin/sh\nexit 0\n");
    nonExecutable.resolve("mvnw").toFile().setExecutable(false);
    assertThatIllegalArgumentException().isThrownBy(() -> policy.detect(host(), nonExecutable));

    Path ambiguous = Files.createDirectory(temporaryDirectory.resolve("ambiguous"));
    Files.writeString(ambiguous.resolve("pom.xml"), "<project/>");
    Files.writeString(ambiguous.resolve("build.gradle"), "plugins {}\n");
    executable(ambiguous.resolve("mvnw"), "#!/bin/sh\nexit 0\n");
    executable(ambiguous.resolve("gradlew"), "#!/bin/sh\nexit 0\n");
    assertThatIllegalArgumentException().isThrownBy(() -> policy.detect(host(), ambiguous));

    Path symlinked = Files.createDirectory(temporaryDirectory.resolve("symlinked"));
    Path external = temporaryDirectory.resolve("external-wrapper");
    executable(external, "#!/bin/sh\nexit 0\n");
    Files.writeString(symlinked.resolve("pom.xml"), "<project/>");
    Files.createSymbolicLink(symlinked.resolve("mvnw"), external);
    assertThatIllegalArgumentException().isThrownBy(() -> policy.detect(host(), symlinked));
  }

  @Test
  void usesAnInstalledMavenWhenTheProjectHasNoWrapperButOnlyForTheLocalExecutor() throws Exception {
    Path root = Files.createDirectories(temporaryDirectory.resolve("toolchain/usr/bin"));
    executable(root.resolve("mvn"), "#!/bin/sh\nexit 0\n");
    LocalToolchain toolchain = new LocalToolchain(
        temporaryDirectory.resolve("toolchain").toString(), "apk");
    Path project = Files.createDirectory(temporaryDirectory.resolve("no-wrapper"));
    Files.writeString(project.resolve("pom.xml"), "<project/>");

    TestCommand local = policy.detect(host(toolchain, "local"), project);
    assertThat(local.buildSystem()).isEqualTo(TestBuildSystem.MAVEN);
    assertThat(local.arguments()).containsExactly(
        "mvn", "--batch-mode", "--no-transfer-progress", "test");

    assertThatIllegalArgumentException()
        .isThrownBy(() -> policy.detect(host(toolchain, "podman"), project));

    // A wrapper still wins over the installed tool.
    executable(project.resolve("mvnw"), "#!/bin/sh\nexit 0\n");
    assertThat(policy.detect(host(toolchain, "local"), project).arguments().get(0))
        .isEqualTo("./mvnw");
  }

  private static void executable(Path path, String content) throws Exception {
    Files.writeString(path, content);
    if (!path.toFile().setExecutable(true)) throw new AssertionError("Could not mark script executable");
  }
}
