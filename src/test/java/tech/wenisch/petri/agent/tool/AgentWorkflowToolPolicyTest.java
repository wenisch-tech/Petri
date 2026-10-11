package tech.wenisch.petri.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tech.wenisch.petri.agent.orchestration.AgentWorkflowRole;

class AgentWorkflowToolPolicyTest {
  @Test
  void qualityRolesCanInspectButCannotMutate() {
    assertThat(AgentWorkflowToolPolicy.allowed(AgentWorkflowRole.ORCHESTRATOR))
        .contains("list_files", "read_file", "search_files", "show_diff")
        .doesNotContain("create_patch", "create_file", "run_tests");
    assertThat(AgentWorkflowToolPolicy.allowed(AgentWorkflowRole.PLANNER))
        .contains("read_file", "search_files", "load_skill")
        .doesNotContain("create_patch", "create_file", "run_tests");
    assertThat(AgentWorkflowToolPolicy.allowed(AgentWorkflowRole.REVIEWER))
        .doesNotContain("create_patch", "create_file", "run_tests");
  }

  @Test
  void onlyImplementerReceivesProposalTools() {
    assertThat(AgentWorkflowToolPolicy.allowed(AgentWorkflowRole.IMPLEMENTER))
        .contains("create_patch", "create_file", "run_tests");
  }
}
