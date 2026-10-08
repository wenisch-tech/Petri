package tech.wenisch.petri.agent.tool;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tech.wenisch.petri.agent.run.TestRunService;

@Component
public class RunTestsTool implements AgentTool {
  private static final ToolDefinition DEFINITION = new ToolDefinition(
      "run_tests",
      "Request a reviewable Maven or Gradle test run. This never executes before human approval.",
      ToolSchemas.object(Map.of(), List.of()));

  private final TestRunService testRuns;

  public RunTestsTool(TestRunService testRuns) {
    this.testRuns = testRuns;
  }

  @Override
  public ToolDefinition definition() { return DEFINITION; }

  @Override
  public Object execute(ToolExecutionContext context, Map<String, Object> input) {
    if (!input.isEmpty()) throw new IllegalArgumentException("run_tests does not accept arguments");
    return testRuns.request(context.worktreeId());
  }
}
