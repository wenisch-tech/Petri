package tech.wenisch.petri.agent.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestCommandPolicyTest {
  @TempDir Path temporaryDirectory;

  private final TestCommandPolicy policy = new TestCommandPolicy();

  @Test
  void permitsOnlyFixedMavenOrGradleWrapperCommands() throws Exception {
    Path maven = Files.createDirectory(temporaryDirectory.resolve("maven"));
    Files.writeString(maven.resolve("pom.xml"), "<project/>");
    executable(maven.resolve("mvnw"), "#!/bin/sh\nexit 0\n");
    TestCommand mavenCommand = policy.detect(maven);
    assertThat(mavenCommand.buildSystem()).isEqualTo(TestBuildSystem.MAVEN);
    assertThat(mavenCommand.arguments()).containsExactly(
        "./mvnw", "--batch-mode", "--no-transfer-progress", "test");

    Path gradle = Files.createDirectory(temporaryDirectory.resolve("gradle"));
    Files.writeString(gradle.resolve("build.gradle.kts"), "plugins {}\n");
    executable(gradle.resolve("gradlew"), "#!/bin/sh\nexit 0\n");
    TestCommand gradleCommand = policy.detect(gradle);
    assertThat(gradleCommand.buildSystem()).isEqualTo(TestBuildSystem.GRADLE);
    assertThat(gradleCommand.arguments()).containsExactly("./gradlew", "--no-daemon", "test");
  }

  @Test
  void rejectsMissingNonExecutableAmbiguousAndSymlinkedWrappers() throws Exception {
    Path missing = Files.createDirectory(temporaryDirectory.resolve("missing"));
    assertThatIllegalArgumentException().isThrownBy(() -> policy.detect(missing));

    Path nonExecutable = Files.createDirectory(temporaryDirectory.resolve("non-executable"));
    Files.writeString(nonExecutable.resolve("pom.xml"), "<project/>");
    Files.writeString(nonExecutable.resolve("mvnw"), "#!/bin/sh\nexit 0\n");
    nonExecutable.resolve("mvnw").toFile().setExecutable(false);
    assertThatIllegalArgumentException().isThrownBy(() -> policy.detect(nonExecutable));

    Path ambiguous = Files.createDirectory(temporaryDirectory.resolve("ambiguous"));
    Files.writeString(ambiguous.resolve("pom.xml"), "<project/>");
    Files.writeString(ambiguous.resolve("build.gradle"), "plugins {}\n");
    executable(ambiguous.resolve("mvnw"), "#!/bin/sh\nexit 0\n");
    executable(ambiguous.resolve("gradlew"), "#!/bin/sh\nexit 0\n");
    assertThatIllegalArgumentException().isThrownBy(() -> policy.detect(ambiguous));

    Path symlinked = Files.createDirectory(temporaryDirectory.resolve("symlinked"));
    Path external = temporaryDirectory.resolve("external-wrapper");
    executable(external, "#!/bin/sh\nexit 0\n");
    Files.writeString(symlinked.resolve("pom.xml"), "<project/>");
    Files.createSymbolicLink(symlinked.resolve("mvnw"), external);
    assertThatIllegalArgumentException().isThrownBy(() -> policy.detect(symlinked));
  }

  @Test
  void usesAnInstalledMavenWhenTheProjectHasNoWrapperButOnlyForTheLocalExecutor() throws Exception {
    Path root = Files.createDirectories(temporaryDirectory.resolve("toolchain/usr/bin"));
    executable(root.resolve("mvn"), "#!/bin/sh\nexit 0\n");
    LocalToolchain toolchain = new LocalToolchain(
        temporaryDirectory.resolve("toolchain").toString(), "apk");
    Path project = Files.createDirectory(temporaryDirectory.resolve("no-wrapper"));
    Files.writeString(project.resolve("pom.xml"), "<project/>");

    TestCommand local = new TestCommandPolicy(toolchain, "local").detect(project);
    assertThat(local.buildSystem()).isEqualTo(TestBuildSystem.MAVEN);
    assertThat(local.arguments()).containsExactly(
        "mvn", "--batch-mode", "--no-transfer-progress", "test");

    assertThatIllegalArgumentException()
        .isThrownBy(() -> new TestCommandPolicy(toolchain, "podman").detect(project));

    // A wrapper still wins over the installed tool.
    executable(project.resolve("mvnw"), "#!/bin/sh\nexit 0\n");
    assertThat(new TestCommandPolicy(toolchain, "local").detect(project).arguments().get(0))
        .isEqualTo("./mvnw");
  }

  private static void executable(Path path, String content) throws Exception {
    Files.writeString(path, content);
    if (!path.toFile().setExecutable(true)) throw new AssertionError("Could not mark script executable");
  }
}
