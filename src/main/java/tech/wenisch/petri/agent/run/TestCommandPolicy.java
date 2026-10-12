package tech.wenisch.petri.agent.run;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class TestCommandPolicy {
  private final LocalToolchain toolchain;
  private final boolean local;

  TestCommandPolicy() {
    this(null, "podman");
  }

  @Autowired
  TestCommandPolicy(
      LocalToolchain toolchain, @Value("${petri.tests.executor:podman}") String executor) {
    this.toolchain = toolchain;
    this.local = "local".equalsIgnoreCase(executor);
  }

  TestCommand detect(Path worktree) {
    boolean mavenBuild = regular(worktree.resolve("pom.xml"));
    boolean gradleBuild = regular(worktree.resolve("build.gradle"))
        || regular(worktree.resolve("build.gradle.kts"));
    boolean maven = mavenBuild && (executable(worktree.resolve("mvnw")) || installed("mvn"));
    boolean gradle = gradleBuild && (executable(worktree.resolve("gradlew")) || installed("gradle"));
    if (maven == gradle) {
      throw new IllegalArgumentException(maven
          ? "Worktree has ambiguous Maven and Gradle test entry points"
          : "Worktree needs a Maven or Gradle wrapper (or an installed mvn/gradle) "
              + "and a matching build file");
    }
    return command(worktree, maven ? TestBuildSystem.MAVEN : TestBuildSystem.GRADLE);
  }

  /** A build tool installed with install_packages; only meaningful for the local executor. */
  private boolean installed(String tool) {
    return local && toolchain != null && toolchain.hasTool(tool);
  }

  TestCommand command(Path worktree, TestBuildSystem buildSystem) {
    return switch (buildSystem) {
      case MAVEN -> {
        requireRegular(worktree.resolve("pom.xml"), "pom.xml");
        if (!executable(worktree.resolve("mvnw")) && installed("mvn")) {
          yield new TestCommand(
              TestBuildSystem.MAVEN, List.of("mvn", "--batch-mode", "--no-transfer-progress", "test"));
        }
        requireExecutable(worktree.resolve("mvnw"), "mvnw");
        yield new TestCommand(
            TestBuildSystem.MAVEN,
            List.of("./mvnw", "--batch-mode", "--no-transfer-progress", "test"));
      }
      case GRADLE -> {
        if (!regular(worktree.resolve("build.gradle"))
            && !regular(worktree.resolve("build.gradle.kts"))) {
          throw new IllegalArgumentException("Gradle build file is missing or unsafe");
        }
        if (!executable(worktree.resolve("gradlew")) && installed("gradle")) {
          yield new TestCommand(TestBuildSystem.GRADLE, List.of("gradle", "--no-daemon", "test"));
        }
        requireExecutable(worktree.resolve("gradlew"), "gradlew");
        yield new TestCommand(
            TestBuildSystem.GRADLE,
            List.of("./gradlew", "--no-daemon", "test"));
      }
    };
  }

  private static void requireRegular(Path path, String name) {
    if (!regular(path)) throw new IllegalArgumentException(name + " is missing or unsafe");
  }

  private static void requireExecutable(Path path, String name) {
    requireRegular(path, name);
    if (!Files.isExecutable(path)) throw new IllegalArgumentException(name + " is not executable");
  }

  private static boolean executable(Path path) {
    return regular(path) && Files.isExecutable(path);
  }

  private static boolean regular(Path path) {
    return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(path);
  }
}
