package tech.wenisch.petri.agent.run;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
class TestCommandPolicy {
  TestCommand detect(Path worktree) {
    boolean maven = regular(worktree.resolve("pom.xml")) && executable(worktree.resolve("mvnw"));
    boolean gradle = (regular(worktree.resolve("build.gradle"))
        || regular(worktree.resolve("build.gradle.kts")))
        && executable(worktree.resolve("gradlew"));
    if (maven == gradle) {
      throw new IllegalArgumentException(maven
          ? "Worktree has ambiguous Maven and Gradle test entry points"
          : "Worktree needs an executable Maven or Gradle wrapper and matching build file");
    }
    return command(worktree, maven ? TestBuildSystem.MAVEN : TestBuildSystem.GRADLE);
  }

  TestCommand command(Path worktree, TestBuildSystem buildSystem) {
    return switch (buildSystem) {
      case MAVEN -> {
        requireRegular(worktree.resolve("pom.xml"), "pom.xml");
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
