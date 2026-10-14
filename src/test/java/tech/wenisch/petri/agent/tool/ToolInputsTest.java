package tech.wenisch.petri.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ToolInputsTest {
  @Test
  void wholeNumbersSentAsStringsAreAccepted() {
    assertThat(ToolInputs.integer(Map.of("maxResults", "20"), "maxResults", 5, 1, 100)).isEqualTo(20);
    assertThat(ToolInputs.integer(Map.of("maxResults", 20), "maxResults", 5, 1, 100)).isEqualTo(20);
    assertThat(ToolInputs.integer(Map.of(), "maxResults", 5, 1, 100)).isEqualTo(5);
  }

  @Test
  void otherTextAndOutOfRangeValuesAreStillRejected() {
    assertThatThrownBy(() -> ToolInputs.integer(Map.of("n", "many"), "n", 5, 1, 100))
        .hasMessageContaining("must be a number");
    assertThatThrownBy(() -> ToolInputs.integer(Map.of("n", "500"), "n", 5, 1, 100))
        .hasMessageContaining("outside its allowed range");
  }
}
