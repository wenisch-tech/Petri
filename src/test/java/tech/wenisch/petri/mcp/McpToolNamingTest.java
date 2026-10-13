package tech.wenisch.petri.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class McpToolNamingTest {
  @Test
  void namesAreModelSafeAndPrefixed() {
    String name = McpToolNaming.expose("Issue tracker!", "create ticket/v2", new HashSet<>());

    assertThat(name).startsWith("mcp__issue-tracker__").matches("[A-Za-z0-9_-]{1,64}");
    assertThat(McpToolNaming.isMcpTool(name)).isTrue();
    assertThat(McpToolNaming.isMcpTool("read_file")).isFalse();
  }

  @Test
  void overlongAndCollidingNamesGetStableUniqueSuffixes() {
    Set<String> taken = new HashSet<>();
    String longTool = "t".repeat(120);
    String first = McpToolNaming.expose("server", longTool, taken);
    taken.add(first);
    String second = McpToolNaming.expose("server", longTool + "x", taken);

    assertThat(first).hasSizeLessThanOrEqualTo(64).matches("[A-Za-z0-9_-]+");
    assertThat(second).hasSizeLessThanOrEqualTo(64).isNotEqualTo(first);
    assertThat(McpToolNaming.expose("server", longTool, new HashSet<>())).isEqualTo(first);

    taken.add("mcp__a__b");
    assertThat(McpToolNaming.expose("a", "b", taken)).isNotEqualTo("mcp__a__b");
  }
}
