package tech.wenisch.petri.agent.run;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import tech.wenisch.petri.git.ProjectWorktreeService;
import tech.wenisch.petri.workspace.WorktreeLocator;

/** Terminal commands for the workspace panel and for the agent's {@code run_command} tool. */
@Service
public class TerminalService {
  private static final Logger log = LoggerFactory.getLogger(TerminalService.class);
  static final int MAX_COMMAND_LENGTH = 4000;

  private final TerminalStateService state;
  private final TerminalRunner runner;
  private final WorktreeLocator worktrees;
  private final ProjectWorktreeService projectWorktrees;
  private final ProjectSandboxSettingRepository settings;

  TerminalService(TerminalStateService state, TerminalRunner runner, WorktreeLocator worktrees,
      ProjectWorktreeService projectWorktrees, ProjectSandboxSettingRepository settings) {
    this.state = state;
    this.runner = runner;
    this.worktrees = worktrees;
    this.projectWorktrees = projectWorktrees;
    this.settings = settings;
  }

  public boolean available() { return runner.available(); }

  public TerminalCommandView request(UUID worktreeId, UUID agentRunId, String command, String reason) {
    if (!runner.available()) throw new IllegalStateException(runner.unavailableReason());
    String safe = validate(command);
    String safeReason = reason == null ? null : reason.strip();
    if (safeReason != null && safeReason.length() > 1000) safeReason = safeReason.substring(0, 1000);
    return state.requestForAgent(worktreeId, agentRunId, safe, safeReason);
  }

  /** Runs a command typed by the signed-in user; the user is the approver. */
  public TerminalCommandView runForUser(UUID worktreeId, String actorEmail, String command) {
    if (!runner.available()) throw new IllegalStateException(runner.unavailableReason());
    TerminalCommandView created = state.createForUser(worktreeId, actorEmail, validate(command));
    return execute(created.id());
  }

  public TerminalCommandView get(UUID id) { return state.get(id); }

  public List<TerminalCommandView> listForAgentRun(UUID agentRunId) {
    return state.listForAgentRun(agentRunId);
  }

  public TerminalCommandView approve(UUID id, String actorEmail) { return state.approve(id, actorEmail); }

  public TerminalCommandView reject(UUID id, String actorEmail) { return state.reject(id, actorEmail); }

  /** Whether the worktree's project auto-approves agent terminal commands. */
  public boolean autoApprove(UUID worktreeId) {
    UUID projectId = projectWorktrees.get(worktreeId).projectId();
    return settings.findById(projectId).map(ProjectSandboxSetting::autoApproveTerminalCommands).orElse(false);
  }

  public TerminalCommandView execute(UUID id) {
    TerminalCommandView started = state.start(id);
    try {
      java.nio.file.Path worktree = worktrees.requireActiveWorktree(started.worktreeId());
      UUID projectId = projectWorktrees.get(started.worktreeId()).projectId();
      return state.complete(id,
          runner.run(projectId, worktree, started.command(), text -> state.progress(id, text)));
    } catch (IOException | RuntimeException exception) {
      log.warn("Terminal command {} could not run", id, exception);
      return state.error(id, "Command could not be started: "
          + (exception.getMessage() == null ? "unknown error" : exception.getMessage()));
    }
  }

  static String validate(String command) {
    if (command == null || command.isBlank()) throw new IllegalArgumentException("A command is required");
    String value = command.strip();
    if (value.length() > MAX_COMMAND_LENGTH || value.indexOf('\0') >= 0) {
      throw new IllegalArgumentException("Command is too long or contains invalid characters");
    }
    return value;
  }
}
