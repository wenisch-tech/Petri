package tech.wenisch.petri.agent.tool;

import java.util.Set;
import tech.wenisch.petri.agent.orchestration.AgentWorkflowRole;

public final class AgentWorkflowToolPolicy {
  private static final Set<String> READ_ONLY = Set.of(
      "list_files", "read_file", "search_files", "show_diff", "list_skills", "load_skill",
      "get_pull_request", "install_packages");
  private static final Set<String> IMPLEMENT = Set.of(
      "list_files", "read_file", "search_files", "show_diff", "list_skills", "load_skill",
      "get_pull_request", "create_patch", "create_file", "run_tests", "install_packages",
      "run_command");

  private AgentWorkflowToolPolicy() {}

  public static Set<String> allowed(AgentWorkflowRole role) {
    if (role == null) return IMPLEMENT;
    return switch (role) {
      case ORCHESTRATOR, PLANNER, REVIEWER -> READ_ONLY;
      case IMPLEMENTER -> IMPLEMENT;
    };
  }
}
