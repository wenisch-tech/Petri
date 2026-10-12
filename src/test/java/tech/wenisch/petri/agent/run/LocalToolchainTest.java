package tech.wenisch.petri.agent.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;

class LocalToolchainTest {
  @Test
  void environmentIsBuiltFromScratchAndPrefersToolchain() {
    LocalToolchain toolchain = new LocalToolchain("/opt/petri/toolchain", "apk");

    Map<String, String> env = toolchain.environment(Path.of("/tmp/home"),
        Map.of("MAVEN_OPTS", "-Dx=1"));

    assertThat(env.get("PATH")).startsWith("/opt/petri/toolchain/usr/local/bin:/opt/petri/toolchain/usr/bin");
    assertThat(env.get("LD_LIBRARY_PATH")).contains("/opt/petri/toolchain/usr/lib");
    assertThat(env).containsEntry("HOME", "/tmp/home").containsEntry("MAVEN_OPTS", "-Dx=1");
    assertThat(env.keySet()).noneMatch(name -> name.startsWith("PETRI_") || name.startsWith("SPRING_"));
  }

  @Test
  void configuredVariablesAreLayeredButNeverReplaceThePath() {
    LocalToolchain toolchain = new LocalToolchain("/opt/petri/toolchain", "apk",
        new CommandEnvironment(Map.of("HTTPS_PROXY", "http://proxy:3128")));

    Map<String, String> env = toolchain.environment(Path.of("/tmp/home"), Map.of(),
        Map.of("NO_PROXY", "internal"));

    assertThat(env).containsEntry("HTTPS_PROXY", "http://proxy:3128")
        .containsEntry("NO_PROXY", "internal");
    assertThat(env.get("PATH")).startsWith("/opt/petri/toolchain/usr/local/bin");
  }

  @Test
  void repairsAbsoluteSymlinksThatOnlyResolveInsideThePrefix(@org.junit.jupiter.api.io.TempDir Path dir)
      throws Exception {
    Path root = dir.resolve("toolchain");
    java.nio.file.Files.createDirectories(root.resolve("usr/bin"));
    java.nio.file.Files.createDirectories(root.resolve("usr/share/java/maven-3/bin"));
    Path real = root.resolve("usr/share/java/maven-3/bin/mvn");
    java.nio.file.Files.writeString(real, "#!/bin/sh\necho maven\n");
    real.toFile().setExecutable(true);
    // Exactly what apk creates: an absolute target that exists only below the prefix.
    java.nio.file.Files.createSymbolicLink(
        root.resolve("usr/bin/mvn"), Path.of("/usr/share/java/maven-3/bin/mvn"));
    // A link to something that exists on the real system must be left alone.
    java.nio.file.Files.createSymbolicLink(root.resolve("usr/bin/sh"), Path.of("/bin/sh"));
    // And one that exists nowhere stays as it is.
    java.nio.file.Files.createSymbolicLink(root.resolve("usr/bin/gone"), Path.of("/nowhere/at/all"));
    LocalToolchain toolchain = new LocalToolchain(root.toString(), "apk");

    assertThat(toolchain.hasTool("mvn")).isFalse();
    assertThat(toolchain.repairLinks()).isEqualTo(1);

    assertThat(java.nio.file.Files.readSymbolicLink(root.resolve("usr/bin/mvn")).isAbsolute()).isFalse();
    assertThat(java.nio.file.Files.isExecutable(root.resolve("usr/bin/mvn"))).isTrue();
    assertThat(toolchain.hasTool("mvn")).isTrue();
    assertThat(java.nio.file.Files.readSymbolicLink(root.resolve("usr/bin/sh")))
        .isEqualTo(Path.of("/bin/sh"));
    assertThat(java.nio.file.Files.readSymbolicLink(root.resolve("usr/bin/gone")))
        .isEqualTo(Path.of("/nowhere/at/all"));
    assertThat(toolchain.repairLinks()).isZero();
  }

  @Test
  void aLinkCannotBeRepairedToSomethingOutsideThePrefix(@org.junit.jupiter.api.io.TempDir Path dir)
      throws Exception {
    Path root = dir.resolve("toolchain");
    java.nio.file.Files.createDirectories(root.resolve("usr/bin"));
    java.nio.file.Files.createDirectories(dir.resolve("outside"));
    java.nio.file.Files.writeString(dir.resolve("outside/secret"), "x");
    java.nio.file.Files.createSymbolicLink(
        root.resolve("usr/bin/escape"), Path.of("/../outside/secret"));
    LocalToolchain toolchain = new LocalToolchain(root.toString(), "apk");

    assertThat(toolchain.repairLinks()).isZero();
  }
}
