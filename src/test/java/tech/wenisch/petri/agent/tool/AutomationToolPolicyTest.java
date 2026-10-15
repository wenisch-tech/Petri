package tech.wenisch.petri.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tech.wenisch.petri.agent.orchestration.AgentWorkflowRole;

class AutomationToolPolicyTest {
  private static AgentTool tool(String name) {
    return new AgentTool() {
      @Override public ToolDefinition definition() {
        return new ToolDefinition(name, name, Map.of());
      }
      @Override public Object execute(ToolExecutionContext context, Map<String, Object> input) {
        return "ran " + name;
      }
    };
  }

  private final AgentToolRegistry registry = new AgentToolRegistry(List.of(
      tool("read_file"), tool("create_patch"), tool("run_command"), tool("install_packages"),
      tool("run_tests"), tool("submit_review")));

  private static ToolExecutionContext automation() {
    return new ToolExecutionContext(UUID.randomUUID(), UUID.randomUUID(), null, UUID.randomUUID());
  }

  @Test
  void automationRunsAreLimitedToReadOnlyToolsAndSubmitReview() {
    assertThat(registry.execute("read_file", automation(), Map.of()).output()).isEqualTo("ran read_file");
    assertThat(registry.execute("submit_review", automation(), Map.of()).output())
        .isEqualTo("ran submit_review");
    for (String forbidden : List.of("create_patch", "run_command", "install_packages", "run_tests")) {
      assertThatThrownBy(() -> registry.execute(forbidden, automation(), Map.of()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("automation");
    }
  }

  @Test
  void submitReviewIsRefusedOutsideAutomationRuns() {
    ToolExecutionContext interactive = new ToolExecutionContext(UUID.randomUUID());

    assertThatThrownBy(() -> registry.execute("submit_review", interactive, Map.of()))
        .hasMessageContaining("only available to automation runs");
    assertThatThrownBy(() -> registry.execute("submit_review",
        new ToolExecutionContext(UUID.randomUUID(), UUID.randomUUID(), AgentWorkflowRole.REVIEWER),
        Map.of())).hasMessageContaining("only available to automation runs");
  }

  @Test
  void automationPolicyHasNoMutatingTool() {
    assertThat(AutomationToolPolicy.allowed()).containsExactlyInAnyOrder(
        "list_files", "read_file", "search_files", "get_pull_request", "scan_vulnerabilities",
        "submit_vulnerability_report", "submit_review");
  }

  @Test
  void explicitNullArgumentsReachTheToolInsteadOfFailingTheCall() {
    var seen = new java.util.concurrent.atomic.AtomicReference<Map<String, Object>>();
    AgentToolRegistry nullable = new AgentToolRegistry(List.of(new AgentTool() {
      @Override public ToolDefinition definition() {
        return new ToolDefinition("read_file", "read_file", Map.of());
      }
      @Override public Object execute(ToolExecutionContext context, Map<String, Object> input) {
        seen.set(input);
        return "ok";
      }
    }));
    Map<String, Object> arguments = new java.util.HashMap<>();
    arguments.put("path", "a.txt");
    arguments.put("maxBytes", null);

    assertThat(nullable.execute("read_file", automation(), arguments).output()).isEqualTo("ok");
    assertThat(seen.get()).containsEntry("path", "a.txt").containsKey("maxBytes");
  }

}

