package tech.wenisch.petri.agent.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class TerminalServiceTest {
  @Test
  void trimsAndAcceptsOrdinaryCommands() {
    assertThat(TerminalService.validate("  ls -la && git status  ")).isEqualTo("ls -la && git status");
  }

  @Test
  void rejectsEmptyOversizedAndNulCommands() {
    assertThatThrownBy(() -> TerminalService.validate("  ")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> TerminalService.validate(null)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> TerminalService.validate("a\0b")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> TerminalService.validate("x".repeat(4001)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
