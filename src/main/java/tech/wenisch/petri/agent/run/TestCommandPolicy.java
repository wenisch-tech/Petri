package tech.wenisch.petri.agent.run;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.springframework.stereotype.Component;
import tech.wenisch.petri.workspace.HostStat;
import tech.wenisch.petri.workspace.WorkspaceHost;

@Component
class TestCommandPolicy {

  TestCommand detect(WorkspaceHost host, Path worktree) {
    boolean mavenBuild = regular(host, worktree.resolve("pom.xml"));
    boolean gradleBuild = regular(host, worktree.resolve("build.gradle"))
        || regular(host, worktree.resolve("build.gradle.kts"));
    boolean maven = mavenBuild && (executable(host, worktree.resolve("mvnw")) || installed(host, "mvn"));
    boolean gradle = gradleBuild
        && (executable(host, worktree.resolve("gradlew")) || installed(host, "gradle"));
    if (maven == gradle) {
      throw new IllegalArgumentException(maven
          ? "Worktree has ambiguous Maven and Gradle test entry points"
          : "Worktree needs a Maven or Gradle wrapper (or an installed mvn/gradle) "
              + "and a matching build file");
    }
    return command(host, worktree, maven ? TestBuildSystem.MAVEN : TestBuildSystem.GRADLE);
  }

  /** A build tool the host offers without a wrapper (locally: installed with install_packages). */
  private static boolean installed(WorkspaceHost host, String tool) {
    return host.processes().hasTool(tool);
  }

  TestCommand command(WorkspaceHost host, Path worktree, TestBuildSystem buildSystem) {
    return switch (buildSystem) {
      case MAVEN -> {
        requireRegular(host, worktree.resolve("pom.xml"), "pom.xml");
        if (!executable(host, worktree.resolve("mvnw")) && installed(host, "mvn")) {
          yield new TestCommand(
              TestBuildSystem.MAVEN, List.of("mvn", "--batch-mode", "--no-transfer-progress", "test"));
        }
        requireExecutable(host, worktree.resolve("mvnw"), "mvnw");
        yield new TestCommand(
            TestBuildSystem.MAVEN,
            List.of("./mvnw", "--batch-mode", "--no-transfer-progress", "test"));
      }
      case GRADLE -> {
        if (!regular(host, worktree.resolve("build.gradle"))
            && !regular(host, worktree.resolve("build.gradle.kts"))) {
          throw new IllegalArgumentException("Gradle build file is missing or unsafe");
        }
        if (!executable(host, worktree.resolve("gradlew")) && installed(host, "gradle")) {
          yield new TestCommand(TestBuildSystem.GRADLE, List.of("gradle", "--no-daemon", "test"));
        }
        requireExecutable(host, worktree.resolve("gradlew"), "gradlew");
        yield new TestCommand(
            TestBuildSystem.GRADLE,
            List.of("./gradlew", "--no-daemon", "test"));
      }
    };
  }

  private static void requireRegular(WorkspaceHost host, Path path, String name) {
    if (!regular(host, path)) throw new IllegalArgumentException(name + " is missing or unsafe");
  }

  private static void requireExecutable(WorkspaceHost host, Path path, String name) {
    requireRegular(host, path, name);
    if (!stat(host, path).executable()) throw new IllegalArgumentException(name + " is not executable");
  }

  private static boolean executable(WorkspaceHost host, Path path) {
    return regular(host, path) && stat(host, path).executable();
  }

  private static boolean regular(WorkspaceHost host, Path path) {
    return stat(host, path).isFile();
  }

  private static HostStat stat(WorkspaceHost host, Path path) {
    try {
      return host.layout().stat(path);
    } catch (IOException exception) {
      throw new IllegalStateException("Could not inspect the worktree", exception);
    }
  }
}
