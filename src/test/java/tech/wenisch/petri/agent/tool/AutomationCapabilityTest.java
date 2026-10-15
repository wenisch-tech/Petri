package tech.wenisch.petri.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import org.junit.jupiter.api.Test;

class AutomationCapabilityTest {
  @Test
  void aSkillGetsOnlyWhatItAsksForPlusTheResultTool() {
    assertThat(AutomationToolPolicy.forCapabilities(Set.of("read_repo"), false))
        .containsExactlyInAnyOrder(
            "list_files", "read_file", "search_files", "get_pull_request", "submit_review");
    assertThat(AutomationToolPolicy.forCapabilities(Set.of("read_repo", "scan"), true))
        .contains("scan_vulnerabilities", "submit_vulnerability_report")
        .doesNotContain("submit_review");
  }

  @Test
  void unknownOrWritingCapabilitiesGrantNothing() {
    assertThat(AutomationToolPolicy.forCapabilities(Set.of("write_proposal", "run_shell"), false))
        .containsExactly("submit_review");
  }

  @Test
  void aSkillThatAsksForNothingStillReadsTheRepository() {
    assertThat(AutomationToolPolicy.forCapabilities(Set.of(), false))
        .contains("read_file", "submit_review").doesNotContain("scan_vulnerabilities");
  }
}
