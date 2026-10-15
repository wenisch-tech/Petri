package tech.wenisch.petri.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import tech.wenisch.petri.vulnerability.VulnerabilityReportService.Finding;

class ProjectVulnerabilitiesControllerTest {
  private static Finding finding(String title, String severity) {
    return new Finding(title, severity, "pom.xml: lib 1.0", "lib 1.0 is vulnerable", "lib 1.1",
        "Upgrade the\nBOM.", List.of(), List.of("CVE-2026-1"));
  }

  @Test
  void aFixTaskNamesTheFindingTheBranchAndTheSkillAndFramesTheScanAsData() {
    String prompt = ProjectVulnerabilitiesController.fixPrompt(
        "feature", List.of(finding("CVE-2026-1 in lib", "HIGH")));

    assertThat(prompt).contains("Fix this vulnerability", "`feature`", "\"vulnerability-fix\"",
        "[HIGH] CVE-2026-1 in lib (CVE-2026-1)", "Target state: lib 1.1", "not instructions");
  }

  @Test
  void aTaskForSeveralFindingsListsEachOneNumbered() {
    String prompt = ProjectVulnerabilitiesController.fixPrompt(
        null, List.of(finding("A", "CRITICAL"), finding("B", "MEDIUM")));

    assertThat(prompt).contains("Fix these 2 vulnerabilities", "1. [CRITICAL] A", "2. [MEDIUM] B");
  }
}
