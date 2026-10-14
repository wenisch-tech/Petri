package tech.wenisch.petri.agent.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.util.List;
import org.junit.jupiter.api.Test;
import tech.wenisch.petri.workspace.WorkspacePaths;
import tools.jackson.databind.json.JsonMapper;

class TrivyScanServiceTest {
  private final TrivyScanService service = new TrivyScanService(
      mock(WorkspacePaths.class), mock(LocalToolchain.class), mock(Confinement.class),
      JsonMapper.builder().build(), "trivy");

  @Test
  void aReportIsSummarisedBySeverityWithTheMostImportantFindingsFirst() {
    String report = """
        {"Trivy":{"Version":"0.76.0"},"Results":[
          {"Target":"pom.xml","Vulnerabilities":[
            {"VulnerabilityID":"CVE-1","PkgName":"lib","InstalledVersion":"1.0","FixedVersion":"1.1","Severity":"HIGH","Title":"t"},
            {"VulnerabilityID":"CVE-2","PkgName":"other","InstalledVersion":"2.0","Severity":"CRITICAL","Title":"u"}]},
          {"Target":".env","Secrets":[{"RuleID":"aws-key","Severity":"CRITICAL","Title":"AWS key","StartLine":3,"Match":"AKIA..."}]}
        ]}
        """;

    var result = service.parse(report, List.of("CRITICAL", "HIGH"));

    assertThat(result.version()).isEqualTo("0.76.0");
    assertThat(result.total()).isEqualTo(3);
    assertThat(result.fixable()).isEqualTo(1);
    assertThat(result.bySeverity()).containsEntry("CRITICAL", 2).containsEntry("HIGH", 1);
    assertThat(result.byType()).containsEntry("vulnerability", 2).containsEntry("secret", 1);
    assertThat(result.findings().get(0).severity()).isEqualTo("CRITICAL");
    // The secret value is never carried into the summary.
    assertThat(result.findings().toString()).doesNotContain("AKIA");
  }

  @Test
  void anEmptyReportSaysSoWithoutClaimingTheProjectIsSecure() {
    var result = service.parse("{\"Results\":[]}", List.of("HIGH"));

    assertThat(result.total()).isZero();
    assertThat(result.note()).contains("not proof");
  }
}
