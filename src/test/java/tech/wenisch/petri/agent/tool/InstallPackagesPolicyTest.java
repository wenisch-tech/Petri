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
  void onlyTheImplementerAndDirectRunsMayRunTerminalCommands() {
    assertThat(AgentWorkflowToolPolicy.allowed(AgentWorkflowRole.IMPLEMENTER)).contains("run_command");
    assertThat(AgentWorkflowToolPolicy.allowed(null)).contains("run_command");
    for (AgentWorkflowRole role : new AgentWorkflowRole[] {AgentWorkflowRole.ORCHESTRATOR,
        AgentWorkflowRole.PLANNER, AgentWorkflowRole.REVIEWER}) {
      assertThat(AgentWorkflowToolPolicy.allowed(role)).doesNotContain("run_command");
    }
  }

  @Test
  void onlyTheImplementerCanWriteFilesOrRunTests() {
    assertThat(AgentWorkflowToolPolicy.allowed(AgentWorkflowRole.PLANNER))
        .doesNotContain("create_patch", "create_file", "run_tests");
    assertThat(AgentWorkflowToolPolicy.allowed(AgentWorkflowRole.IMPLEMENTER))
        .contains("create_patch", "create_file", "run_tests");
  }
}
