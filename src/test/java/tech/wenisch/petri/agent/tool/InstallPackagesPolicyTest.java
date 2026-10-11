package tech.wenisch.petri.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tech.wenisch.petri.agent.orchestration.AgentWorkflowRole;

class InstallPackagesPolicyTest {
  @Test
  void everyWorkflowRoleAndDirectRunsMayRequestPackageInstalls() {
    for (AgentWorkflowRole role : AgentWorkflowRole.values()) {
      assertThat(AgentWorkflowToolPolicy.allowed(role)).contains("install_packages");
    }
    assertThat(AgentWorkflowToolPolicy.allowed(null)).contains("install_packages");
  }

  @Test
  void onlyTheImplementerCanWriteFilesOrRunTests() {
    assertThat(AgentWorkflowToolPolicy.allowed(AgentWorkflowRole.PLANNER))
        .doesNotContain("create_patch", "create_file", "run_tests");
    assertThat(AgentWorkflowToolPolicy.allowed(AgentWorkflowRole.IMPLEMENTER))
        .contains("create_patch", "create_file", "run_tests");
  }
}
