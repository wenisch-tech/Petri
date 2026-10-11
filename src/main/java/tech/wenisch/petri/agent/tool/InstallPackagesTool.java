package tech.wenisch.petri.agent.tool;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tech.wenisch.petri.agent.run.PackageInstallService;
import tech.wenisch.petri.agent.run.PackageInstallView;

@Component
public class InstallPackagesTool implements AgentTool {
  private static final ToolDefinition DEFINITION = new ToolDefinition(
      "install_packages",
      "Request installation of Alpine Linux packages (apk) needed to implement or test the task. "
          + "The request is a proposal: it runs only after user approval unless this project "
          + "auto-approves installs. Request every package you need in a single call.",
      ToolSchemas.object(Map.of(
          "packages", Map.of("type", "array", "items", Map.of("type", "string"),
              "description", "Alpine package names, for example jq or python3"),
          "reason", ToolSchemas.string("Why these packages are needed")),
          List.of("packages", "reason")));

  private final PackageInstallService installs;

  public InstallPackagesTool(PackageInstallService installs) {
    this.installs = installs;
  }

  @Override
  public ToolDefinition definition() { return DEFINITION; }

  @Override
  @SuppressWarnings("unchecked")
  public Object execute(ToolExecutionContext context, Map<String, Object> input) {
    Object packages = input.get("packages");
    if (!(packages instanceof List<?> list)) {
      throw new IllegalArgumentException("packages must be an array of package names");
    }
    List<String> names = list.stream().map(value -> value instanceof String text ? text : "").toList();
    Object reason = input.get("reason");
    PackageInstallView view = installs.request(context.worktreeId(), context.agentRunId(), names,
        reason instanceof String text ? text : null);
    return Map.of("packageInstallId", view.id(), "packages", view.packages(), "status",
        view.status().name(),
        "note", "Installation is pending approval unless auto-approved; do not assume the packages "
            + "are available until a later tool result or run_tests shows them.");
  }
}
