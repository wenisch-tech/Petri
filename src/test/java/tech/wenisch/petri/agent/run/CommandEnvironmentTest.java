package tech.wenisch.petri.agent.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.Map;
import org.junit.jupiter.api.Test;

class CommandEnvironmentTest {
  @Test
  void forwardsOnlyTheProxyFamilyAndMirrorsCase() {
    CommandEnvironment environment = new CommandEnvironment(Map.of(
        "HTTPS_PROXY", "http://proxy:3128", "no_proxy", "localhost",
        "PETRI_ADMIN_PASSWORD", "secret", "SPRING_DATASOURCE_PASSWORD", "secret"));

    assertThat(environment.extra(Map.of())).containsOnly(
        Map.entry("HTTPS_PROXY", "http://proxy:3128"), Map.entry("https_proxy", "http://proxy:3128"),
        Map.entry("no_proxy", "localhost"), Map.entry("NO_PROXY", "localhost"));
  }

  @Test
  void proxyForwardingCanBeSwitchedOff() {
    CommandEnvironment environment = new CommandEnvironment(Map.of(
        "HTTPS_PROXY", "http://proxy:3128", "PETRI_COMMAND_ENV_FORWARD_PROXY", "false",
        "PETRI_COMMAND_ENV_FOO", "bar"));

    assertThat(environment.extra(Map.of())).containsOnly(Map.entry("FOO", "bar"));
  }

  @Test
  void profileOverridesInstanceOverridesForwarded() {
    CommandEnvironment environment = new CommandEnvironment(Map.of(
        "HTTPS_PROXY", "http://forwarded", "PETRI_COMMAND_ENV_HTTPS_PROXY", "http://instance"));

    assertThat(environment.extra(Map.of()).get("HTTPS_PROXY")).isEqualTo("http://instance");
    assertThat(environment.extra(Map.of("HTTPS_PROXY", "http://profile")).get("HTTPS_PROXY"))
        .isEqualTo("http://profile");
  }

  @Test
  void reservedNamesAreIgnoredAtMergeAndRejectedOnParse() {
    CommandEnvironment environment = new CommandEnvironment(Map.of("PETRI_COMMAND_ENV_PATH", "/evil"));

    assertThat(environment.extra(Map.of("HOME", "/evil", "LD_PRELOAD", "/evil.so"))).isEmpty();
    assertThatIllegalArgumentException().isThrownBy(() -> CommandEnvironment.parse("PATH=/evil"))
        .withMessageContaining("reserved");
    assertThatIllegalArgumentException().isThrownBy(() -> CommandEnvironment.parse("PETRI_X=1"))
        .withMessageContaining("reserved");
  }

  @Test
  void parseValidatesSyntaxAndLimits() {
    assertThat(CommandEnvironment.parse("# note\nA=1\n\nB = x=y\n"))
        .containsEntry("A", "1").containsEntry("B", " x=y");
    assertThatIllegalArgumentException().isThrownBy(() -> CommandEnvironment.parse("1BAD=1"));
    assertThatIllegalArgumentException().isThrownBy(() -> CommandEnvironment.parse("NOEQUALS"));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> CommandEnvironment.parse("A=" + "x".repeat(5000)));
    String many = String.join("\n", java.util.stream.IntStream.range(0, 51).mapToObj(i -> "V" + i + "=1").toList());
    assertThatIllegalArgumentException().isThrownBy(() -> CommandEnvironment.parse(many))
        .withMessageContaining("At most");
  }
}
