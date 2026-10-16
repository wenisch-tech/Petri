package tech.wenisch.petri.web;

import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tech.wenisch.petri.agent.change.ChangeProposalDiffView;
import tech.wenisch.petri.agent.change.ChangeProposalService;
import tech.wenisch.petri.agent.change.ChangeProposalView;
import tech.wenisch.petri.agent.model.ModelCredentialService;
import tech.wenisch.petri.agent.model.ModelCredentialStatus;
import tech.wenisch.petri.agent.model.ModelProvider;
import tech.wenisch.petri.agent.model.ReasoningEffort;
import tech.wenisch.petri.agent.model.TextVerbosity;
import tech.wenisch.petri.agent.orchestration.AgentChatQueueService;
import tech.wenisch.petri.agent.orchestration.AgentChatAutomationService;
import tech.wenisch.petri.agent.orchestration.AgentChatView;
import tech.wenisch.petri.agent.orchestration.AgentRunLauncher;
import tech.wenisch.petri.agent.orchestration.AgentRunService;
import tech.wenisch.petri.agent.orchestration.AgentRunView;
import tech.wenisch.petri.agent.orchestration.CreateAgentRunCommand;
import tech.wenisch.petri.agent.orchestration.AgentWorkflowService;
import tech.wenisch.petri.agent.orchestration.AgentWorkflowView;
import tech.wenisch.petri.agent.run.PackageInstallService;
import tech.wenisch.petri.agent.run.PackageInstallView;
import tech.wenisch.petri.agent.run.ProjectApprovalSettings;
import tech.wenisch.petri.agent.run.TerminalCommandView;
import tech.wenisch.petri.agent.run.TestSandboxProfileService;
import tech.wenisch.petri.agent.run.TerminalService;
import tech.wenisch.petri.agent.run.TestRunService;
import tech.wenisch.petri.agent.run.TestRunView;
import tech.wenisch.petri.git.ProjectWorktreeService;
import tech.wenisch.petri.git.WorkspaceDiffView;
import tech.wenisch.petri.git.WorkspaceGitDiffService;
import tech.wenisch.petri.security.AppUser;
import tech.wenisch.petri.security.AppUserRepository;
import tech.wenisch.petri.workspace.WorkspaceModelPreferenceService;
import tech.wenisch.petri.workspace.WorkspaceModelPreferenceView;
import tech.wenisch.petri.project.ProjectAccessService;

/** JSON collaboration endpoints; unlike legacy /chat posts these never navigate the workspace. */
@RestController
@RequestMapping("/workspace/api")
public class WorkspaceCollaborationController {
  private final ProjectWorktreeService worktrees;
  private final WorkspaceGitDiffService gitDiffs;
  private final WorkspaceModelPreferenceService preferences;
  private final ModelCredentialService credentials;
  private final AgentRunService runs;
  private final AgentWorkflowService workflows;
  private final AgentRunLauncher launcher;
  private final AgentChatQueueService queue;
  private final ChangeProposalService changes;
  private final TestRunService tests;
  private final PackageInstallService packageInstalls;
  private final TerminalService terminal;
  private final TestSandboxProfileService sandboxSettings;
  private final AppUserRepository users;
  private final AgentChatAutomationService automation;
  private final ProjectAccessService access;

  public WorkspaceCollaborationController(
      ProjectWorktreeService worktrees,
      WorkspaceGitDiffService gitDiffs,
      WorkspaceModelPreferenceService preferences,
      ModelCredentialService credentials,
      AgentRunService runs,
      AgentWorkflowService workflows,
      AgentRunLauncher launcher,
      AgentChatQueueService queue,
      ChangeProposalService changes,
      TestRunService tests,
      PackageInstallService packageInstalls,
      TerminalService terminal,
      TestSandboxProfileService sandboxSettings,
      AppUserRepository users,
      AgentChatAutomationService automation,
      ProjectAccessService access) {
    this.worktrees = worktrees;
    this.gitDiffs = gitDiffs;
    this.preferences = preferences;
    this.credentials = credentials;
    this.runs = runs;
    this.workflows = workflows;
    this.launcher = launcher;
    this.queue = queue;
    this.changes = changes;
    this.packageInstalls = packageInstalls;
    this.terminal = terminal;
    this.sandboxSettings = sandboxSettings;
    this.tests = tests;
    this.users = users;
    this.automation = automation;
    this.access = access;
  }

  @GetMapping("/worktrees/{worktreeId}/changes")
  public List<tech.wenisch.petri.git.WorkspaceChangeView> changes(
      @PathVariable UUID worktreeId, Principal principal) {
    access.requireWorktree(worktreeId, principal.getName());
    return gitDiffs.changes(worktreeId);
  }

  @GetMapping("/worktrees/{worktreeId}/diff")
  public WorkspaceDiffView diff(
      @PathVariable UUID worktreeId, @RequestParam String path, Principal principal) {
    access.requireWorktree(worktreeId, principal.getName());
    return gitDiffs.diff(worktreeId, path);
  }

  @GetMapping("/worktrees/{worktreeId}/proposals/{proposalId}/diff")
  public ChangeProposalDiffView proposalDiff(
      @PathVariable UUID worktreeId, @PathVariable UUID proposalId, Principal principal) {
    access.requireWorktree(worktreeId, principal.getName());
    return changes.diff(proposalId, worktreeId);
  }

  @PutMapping("/projects/{projectId}/model-preference")
  public WorkspaceModelPreferenceView saveModelPreference(
      @PathVariable UUID projectId, @RequestBody ModelPreferenceRequest request, Principal principal) {
    access.requireProject(projectId, principal.getName());
    AppUser actor = actor(principal);
    ModelCredentialStatus target = credentials.resolve(
        actor.getId(), request.provider(), request.providerInstanceId());
    return preferences.save(
        projectId, actor.getId(), actor.getEmail(), target.provider(), target.id(), request.model());
  }

  /** One chat of the selector with what it is doing right now. */
  public record WorkspaceChatListItem(
      UUID id, String title, String mode, String state, boolean active, int queued) {}

  @GetMapping("/worktrees/{worktreeId}/chats")
  public List<WorkspaceChatListItem> chats(@PathVariable UUID worktreeId, Principal principal) {
    access.requireWorktree(worktreeId, principal.getName());
    AppUser actor = actor(principal);
    List<WorkspaceChatListItem> items = new java.util.ArrayList<>();
    for (AgentChatView chat : runs.listChats(worktreeId, actor.getId())) {
      String state = chatState(chat, actor);
      boolean active = List.of("RUNNING", "QUEUED", "STOPPING", "WAITING", "HOLD").contains(state);
      items.add(new WorkspaceChatListItem(chat.id(), chat.title(), chat.executionMode().name(),
          state, active, chat.queuedRunCount()));
    }
    // Active chats first; the order inside each group stays most recently updated first.
    items.sort(java.util.Comparator.comparing((WorkspaceChatListItem item) -> !item.active()));
    return items;
  }

  /**
   * RUNNING, QUEUED, STOPPING, WAITING (needs the person), HOLD, or the idle outcomes IDLE,
   * FAILED and STOPPED.
   */
  private String chatState(AgentChatView chat, AppUser actor) {
    if (chat.executionMode() == tech.wenisch.petri.agent.orchestration.AgentChatExecutionMode.ORCHESTRATED) {
      String result = "IDLE";
      for (AgentWorkflowView workflow : workflows.chatWorkflows(chat.id(), actor.getId())) {
        switch (workflow.status()) {
          case WAITING_FOR_USER -> { return "WAITING"; }
          case HUMAN_HOLD -> { return "HOLD"; }
          case CANCELLING -> { return "STOPPING"; }
          case PENDING, RUNNING -> { return "RUNNING"; }
          case CANCELLED -> result = "STOPPED";
          default -> result = "IDLE";
        }
      }
      return result;
    }
    boolean pending = false;
    for (AgentRunView run : chat.runs()) {
      switch (run.status()) {
        case RUNNING -> { return "RUNNING"; }
        case CANCELLATION_REQUESTED -> { return "STOPPING"; }
        case PENDING -> pending = true;
        default -> { }
      }
    }
    if (pending) return chat.queuePaused() ? "HOLD" : "QUEUED";
    if (chat.runs().isEmpty()) return "IDLE";
    return switch (chat.runs().get(chat.runs().size() - 1).status()) {
      case FAILED, BUDGET_EXHAUSTED -> "FAILED";
      case CANCELLED -> "STOPPED";
      default -> "IDLE";
    };
  }

  /** Stops what a direct chat is doing: the running turn and everything queued behind it. */
  @PostMapping("/chats/{chatId}/cancel")
  public WorkspaceChatSnapshot cancel(@PathVariable UUID chatId, Principal principal) {
    AppUser actor = actor(principal);
    runs.chat(chatId, actor.getId());
    for (AgentRunView run : runs.chatRuns(chatId)) {
      switch (run.status()) {
        case PENDING, RUNNING ->
            runs.requestCancellation(run.id(), actor.getId(), "workspace:" + actor.getEmail());
        default -> { }
      }
    }
    return snapshotFor(chatId, actor);
  }

  @GetMapping("/chats/{chatId}/snapshot")
  public WorkspaceChatSnapshot snapshot(@PathVariable UUID chatId, Principal principal) {
    return snapshotFor(chatId, actor(principal));
  }

  @PostMapping("/worktrees/{worktreeId}/chats")
  public WorkspaceChatSnapshot createChat(
      @PathVariable UUID worktreeId, @RequestBody WorkspaceMessageRequest request, Principal principal) {
    access.requireWorktree(worktreeId, principal.getName());
    AppUser actor = actor(principal);
    AgentRunView run = runs.create(worktreeId,
        command(request, worktrees.get(worktreeId).projectId(), actor), actor.getId(), "web:" + actor.getEmail());
    launcher.launch(run.id(), run.worktreeId(), actor.getId(), "workspace:" + actor.getEmail());
    return snapshotFor(run.chatId(), actor);
  }

  @PostMapping("/chats/{chatId}/messages")
  public WorkspaceChatSnapshot enqueue(
      @PathVariable UUID chatId, @RequestBody WorkspaceMessageRequest request, Principal principal) {
    AppUser actor = actor(principal);
    AgentChatView chat = runs.chat(chatId, actor.getId());
    runs.enqueue(chatId, command(request, worktrees.get(chat.worktreeId()).projectId(), actor), actor.getId(), "workspace:" + actor.getEmail());
    queue.resume(chatId, false);
    return snapshotFor(chatId, actor);
  }

  @PutMapping("/chats/{chatId}/automation")
  public WorkspaceChatSnapshot automation(
      @PathVariable UUID chatId, @RequestBody AutomationRequest request, Principal principal) {
    AppUser actor = actor(principal);
    runs.updateAutomation(chatId, actor.getId(), request.autoApproveChanges(), request.autoApproveTests());
    automation.processExisting(chatId, actor.getId());
    return snapshotFor(chatId, actor);
  }

  @PostMapping("/proposals/{proposalId}/approve-and-apply")
  public ChangeProposalView approveAndApply(@PathVariable UUID proposalId, Principal principal) {
    AppUser actor = actor(principal);
    ChangeProposalView value = changes.get(proposalId);
    AgentRunView run = runs.get(value.agentRunId());
    runs.chat(run.chatId(), actor.getId());
    changes.get(proposalId, run.worktreeId());
    ChangeProposalView applied = changes.approveAndApply(proposalId, actor.getEmail());
    queue.approvalsChanged(run.chatId());
    return applied;
  }

  @PostMapping("/chats/{chatId}/proposals/{proposalId}/reject")
  public WorkspaceChatSnapshot rejectProposal(
      @PathVariable UUID chatId, @PathVariable UUID proposalId, Principal principal) {
    AppUser actor = actor(principal);
    AgentRunView run = ownedRunForProposal(chatId, proposalId, actor);
    changes.reject(proposalId, actor.getEmail());
    queue.approvalsChanged(run.chatId());
    return snapshotFor(chatId, actor);
  }

  @PostMapping("/chats/{chatId}/tests/{testRunId}/run")
  public WorkspaceChatSnapshot runTest(
      @PathVariable UUID chatId, @PathVariable UUID testRunId, Principal principal) {
    AppUser actor = actor(principal);
    AgentRunView run = ownedRunForTest(chatId, testRunId, actor);
    TestRunView test = tests.get(testRunId, run.worktreeId());
    if (test.status() == tech.wenisch.petri.agent.run.TestRunStatus.PROPOSED) {
      tests.approve(testRunId, actor.getEmail());
    }
    tests.executeAsync(testRunId, () -> queue.approvalsChanged(chatId));
    queue.approvalsChanged(chatId);
    return snapshotFor(chatId, actor);
  }

  @PostMapping("/chats/{chatId}/tests/{testRunId}/reject")
  public WorkspaceChatSnapshot rejectTest(
      @PathVariable UUID chatId, @PathVariable UUID testRunId, Principal principal) {
    AppUser actor = actor(principal);
    AgentRunView run = ownedRunForTest(chatId, testRunId, actor);
    tests.reject(testRunId, actor.getEmail());
    queue.approvalsChanged(run.chatId());
    return snapshotFor(chatId, actor);
  }

  @PostMapping("/chats/{chatId}/packages/{installId}/approve")
  public WorkspaceChatSnapshot approvePackages(
      @PathVariable UUID chatId, @PathVariable UUID installId, Principal principal) {
    AppUser actor = actor(principal);
    ownedRunForPackages(chatId, installId, actor);
    PackageInstallView value = packageInstalls.get(installId);
    if (value.status() == tech.wenisch.petri.agent.run.PackageInstallStatus.PROPOSED) {
      packageInstalls.approve(installId, actor.getEmail());
    }
    packageInstalls.executeAsync(installId, () -> queue.approvalsChanged(chatId));
    queue.approvalsChanged(chatId);
    return snapshotFor(chatId, actor);
  }

  @PostMapping("/chats/{chatId}/packages/{installId}/reject")
  public WorkspaceChatSnapshot rejectPackages(
      @PathVariable UUID chatId, @PathVariable UUID installId, Principal principal) {
    AppUser actor = actor(principal);
    ownedRunForPackages(chatId, installId, actor);
    packageInstalls.reject(installId, actor.getEmail());
    queue.approvalsChanged(chatId);
    return snapshotFor(chatId, actor);
  }

  @PostMapping("/chats/{chatId}/terminal/{commandId}/approve")
  public WorkspaceChatSnapshot approveCommand(
      @PathVariable UUID chatId, @PathVariable UUID commandId, Principal principal) {
    AppUser actor = actor(principal);
    ownedCommand(chatId, commandId, actor);
    if (terminal.get(commandId).status() == tech.wenisch.petri.agent.run.TerminalCommandStatus.PROPOSED) {
      terminal.approve(commandId, actor.getEmail());
    }
    terminal.executeAsync(commandId, () -> queue.approvalsChanged(chatId));
    queue.approvalsChanged(chatId);
    return snapshotFor(chatId, actor);
  }

  @PostMapping("/chats/{chatId}/terminal/{commandId}/reject")
  public WorkspaceChatSnapshot rejectCommand(
      @PathVariable UUID chatId, @PathVariable UUID commandId, Principal principal) {
    AppUser actor = actor(principal);
    ownedCommand(chatId, commandId, actor);
    terminal.reject(commandId, actor.getEmail());
    queue.approvalsChanged(chatId);
    return snapshotFor(chatId, actor);
  }

  /** Runs a command typed in the workspace terminal panel as the signed-in user. */
  @PostMapping("/worktrees/{worktreeId}/terminal")
  public TerminalCommandView runTerminal(
      @PathVariable UUID worktreeId, @RequestBody TerminalRequest request, Principal principal) {
    AppUser actor = actor(principal);
    access.requireWorktree(worktreeId, actor.getEmail());
    return terminal.runForUser(worktreeId, actor.getEmail(), request.command());
  }

  @GetMapping("/projects/{projectId}/approval-settings")
  public ProjectApprovalSettings approvalSettings(
      @PathVariable UUID projectId, Principal principal) {
    access.requireProject(projectId, principal.getName());
    return sandboxSettings.approvalSettings(projectId);
  }

  @PutMapping("/projects/{projectId}/approval-settings")
  public ProjectApprovalSettings saveApprovalSettings(
      @PathVariable UUID projectId, @RequestBody ApprovalSettingsRequest request,
      Principal principal) {
    access.requireProject(projectId, principal.getName());
    return sandboxSettings.saveApprovalSettings(projectId, request.autoApprovePackageInstalls(),
        request.autoApproveTerminalCommands(), principal.getName());
  }

  @GetMapping("/terminal/status")
  public TerminalStatus terminalStatus() {
    return new TerminalStatus(terminal.available());
  }

  private void ownedCommand(UUID chatId, UUID commandId, AppUser actor) {
    runs.chat(chatId, actor.getId());
    for (AgentRunView run : runs.chatRuns(chatId)) {
      for (TerminalCommandView value : terminal.listForAgentRun(run.id())) {
        if (value.id().equals(commandId)) return;
      }
    }
    for (AgentWorkflowView workflow : workflows.chatWorkflows(chatId, actor.getId())) {
      for (TerminalCommandView value : workflow.terminalCommands()) {
        if (value.id().equals(commandId)) return;
      }
    }
    throw new IllegalArgumentException("Terminal command belongs to another chat");
  }

  private void ownedRunForPackages(UUID chatId, UUID installId, AppUser actor) {
    runs.chat(chatId, actor.getId());
    for (AgentRunView run : runs.chatRuns(chatId)) {
      for (PackageInstallView value : packageInstalls.listForAgentRun(run.id())) {
        if (value.id().equals(installId)) return;
      }
    }
    for (AgentWorkflowView workflow : workflows.chatWorkflows(chatId, actor.getId())) {
      for (PackageInstallView value : workflow.packageInstalls()) {
        if (value.id().equals(installId)) return;
      }
    }
    throw new IllegalArgumentException("Package install belongs to another chat");
  }

  private WorkspaceChatSnapshot snapshotFor(UUID chatId, AppUser actor) {
    AgentChatView chat = runs.chat(chatId, actor.getId());
    List<AgentRunView> values = runs.chatRuns(chatId);
    List<WorkspaceChatRun> reviewed = values.stream().map(run -> new WorkspaceChatRun(
        run, changes.listForAgentRun(run.id()), tests.listForAgentRun(run.id()),
        packageInstalls.listForAgentRun(run.id()), terminal.listForAgentRun(run.id()))).toList();
    return new WorkspaceChatSnapshot(worktrees.get(chat.worktreeId()).projectId(), chat, reviewed,
        runs.chatSummaries(chatId), workflows.chatWorkflows(chatId, actor.getId()));
  }

  private AgentRunView ownedRunForProposal(UUID chatId, UUID proposalId, AppUser actor) {
    AgentRunView run = runs.get(changes.get(proposalId).agentRunId());
    if (!run.chatId().equals(chatId)) throw new IllegalArgumentException("Proposal belongs to another chat");
    runs.chat(chatId, actor.getId());
    changes.get(proposalId, run.worktreeId());
    return run;
  }

  private AgentRunView ownedRunForTest(UUID chatId, UUID testRunId, AppUser actor) {
    runs.chat(chatId, actor.getId());
    for (AgentRunView run : runs.chatRuns(chatId)) {
      for (TestRunView test : tests.listForAgentRun(run.id())) {
        if (test.id().equals(testRunId)) return run;
      }
    }
    throw new IllegalArgumentException("Test run belongs to another chat");
  }

  private CreateAgentRunCommand command(
      WorkspaceMessageRequest request, UUID projectId, AppUser actor) {
    WorkspaceModelPreferenceView preference = preferences.get(projectId, actor.getId());
    ModelProvider provider = request.provider();
    UUID instanceId = request.providerInstanceId();
    if (provider == null && instanceId == null && preference != null) {
      // Prefer the saved choice while the user can still call it.
      boolean stillUsable = credentials.usable(actor.getId()).stream().anyMatch(value ->
          preference.providerInstanceId() != null
              ? value.id().equals(preference.providerInstanceId())
              : value.provider() == preference.provider());
      if (stillUsable) {
        provider = preference.provider();
        instanceId = preference.providerInstanceId();
      }
    }
    if (provider == null && instanceId == null) {
      List<ModelCredentialStatus> usable = credentials.usable(actor.getId());
      ModelCredentialStatus fallback = usable.stream()
          .filter(value -> value.provider() == ModelProvider.OLLAMA).findFirst()
          .orElse(usable.isEmpty() ? null : usable.getFirst());
      if (fallback != null) {
        provider = fallback.provider();
        instanceId = fallback.id();
      }
    }
    String model = request.model();
    boolean sameTarget = preference != null && (instanceId != null
        ? instanceId.equals(preference.providerInstanceId())
        : preference.provider() == provider);
    if ((model == null || model.isBlank()) && sameTarget) {
      model = preference.model();
    }
    return new CreateAgentRunCommand(request.prompt(), provider, request.maxModelTurns(),
        model, request.maxOutputTokens(), request.contextWindowTokens(),
        request.reasoningEffort(), request.textVerbosity(), instanceId);
  }

  private AppUser actor(Principal principal) {
    return users.findByEmailIgnoreCase(principal.getName())
        .orElseThrow(() -> new IllegalArgumentException("Unknown user"));
  }

  public record TerminalRequest(String command) {}
  public record ApprovalSettingsRequest(
      boolean autoApprovePackageInstalls, boolean autoApproveTerminalCommands) {}
  public record TerminalStatus(boolean available) {}
  public record ModelPreferenceRequest(
      ModelProvider provider, UUID providerInstanceId, String model) {}
  public record WorkspaceMessageRequest(
      String prompt, ModelProvider provider, UUID providerInstanceId, Integer maxModelTurns,
      String model,
      Integer maxOutputTokens, Integer contextWindowTokens,
      ReasoningEffort reasoningEffort, TextVerbosity textVerbosity) {}
  public record AutomationRequest(boolean autoApproveChanges, boolean autoApproveTests) {}
  public record WorkspaceChatRun(
      AgentRunView run, List<ChangeProposalView> proposals, List<TestRunView> tests,
      List<PackageInstallView> packageInstalls, List<TerminalCommandView> terminalCommands) {}
  public record WorkspaceChatSnapshot(
      UUID projectId, AgentChatView chat, List<WorkspaceChatRun> runs,
      List<tech.wenisch.petri.agent.orchestration.AgentChatSummaryView> summaries,
      List<AgentWorkflowView> workflows) {}
}
