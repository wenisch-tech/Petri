package tech.wenisch.petri.agent.tool;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tech.wenisch.petri.agent.run.TerminalCommandView;
import tech.wenisch.petri.agent.run.TerminalService;

@Component
public class RunCommandTool implements AgentTool {
  private static final ToolDefinition DEFINITION = new ToolDefinition(
      "run_command",
      "Run a shell command in the project's worktree terminal. It runs as an unprivileged user "
          + "confined to the worktree and only after user approval (unless the project "
          + "auto-approves). The result contains the exit code and output. Use it for builds, "
          + "linters, formatters or inspecting tools; do not use it to read or edit files when a "
          + "file tool can.",
      ToolSchemas.object(Map.of(
          "command", ToolSchemas.string("Shell command executed with sh -c in the worktree root"),
          "reason", ToolSchemas.string("Why this command is needed")),
          List.of("command", "reason")));

  private final TerminalService terminal;

  public RunCommandTool(TerminalService terminal) {
    this.terminal = terminal;
  }

  @Override
  public ToolDefinition definition() { return DEFINITION; }

  @Override
  public Object execute(ToolExecutionContext context, Map<String, Object> input) {
    Object command = input.get("command");
    Object reason = input.get("reason");
    TerminalCommandView view = terminal.request(context.worktreeId(), context.agentRunId(),
        command instanceof String text ? text : null, reason instanceof String text ? text : null);
    return Map.of("terminalCommandId", view.id(), "status", view.status().name(),
        "note", "The command is pending approval unless auto-approved; its output is returned "
            + "once it has run.");
  }
}
