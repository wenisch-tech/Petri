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
}
