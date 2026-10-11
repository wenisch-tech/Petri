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
    if (!credentials.status(request.provider()).configured()) {
      throw new IllegalStateException(request.provider() + " credentials are not configured");
    }
    return preferences.save(projectId, actor.getId(), actor.getEmail(), request.provider(), request.model());
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
    tests.execute(testRunId);
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

  private WorkspaceChatSnapshot snapshotFor(UUID chatId, AppUser actor) {
    AgentChatView chat = runs.chat(chatId, actor.getId());
    List<AgentRunView> values = runs.chatRuns(chatId);
    List<WorkspaceChatRun> reviewed = values.stream().map(run -> new WorkspaceChatRun(
        run, changes.listForAgentRun(run.id()), tests.listForAgentRun(run.id()))).toList();
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
    if (provider == null && preference != null && credentials.status(preference.provider()).configured()) {
      provider = preference.provider();
    }
    if (provider == null) {
      provider = credentials.status(ModelProvider.OLLAMA).configured() ? ModelProvider.OLLAMA
          : java.util.Arrays.stream(ModelProvider.values())
              .filter(candidate -> credentials.status(candidate).configured()).findFirst()
              .orElse(ModelProvider.OLLAMA);
    }
    String model = request.model();
    if ((model == null || model.isBlank()) && preference != null && preference.provider() == provider) {
      model = preference.model();
    }
    return new CreateAgentRunCommand(request.prompt(), provider, request.maxModelTurns(),
        model, request.maxOutputTokens(), request.contextWindowTokens(),
        request.reasoningEffort(), request.textVerbosity());
  }

  private AppUser actor(Principal principal) {
    return users.findByEmailIgnoreCase(principal.getName())
        .orElseThrow(() -> new IllegalArgumentException("Unknown user"));
  }

  public record ModelPreferenceRequest(ModelProvider provider, String model) {}
  public record WorkspaceMessageRequest(
      String prompt, ModelProvider provider, Integer maxModelTurns, String model,
      Integer maxOutputTokens, Integer contextWindowTokens,
      ReasoningEffort reasoningEffort, TextVerbosity textVerbosity) {}
  public record AutomationRequest(boolean autoApproveChanges, boolean autoApproveTests) {}
  public record WorkspaceChatRun(
      AgentRunView run, List<ChangeProposalView> proposals, List<TestRunView> tests) {}
  public record WorkspaceChatSnapshot(
      UUID projectId, AgentChatView chat, List<WorkspaceChatRun> runs,
      List<tech.wenisch.petri.agent.orchestration.AgentChatSummaryView> summaries,
      List<AgentWorkflowView> workflows) {}
}
