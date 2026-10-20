package tech.wenisch.petri.web;

import java.security.Principal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tech.wenisch.petri.agent.change.ChangeProposalService;
import tech.wenisch.petri.agent.change.ChangeProposalView;
import tech.wenisch.petri.agent.model.ModelCredentialService;
import tech.wenisch.petri.agent.model.ModelCredentialStatus;
import tech.wenisch.petri.agent.model.ModelProvider;
import tech.wenisch.petri.agent.model.ReasoningEffort;
import tech.wenisch.petri.agent.model.TextVerbosity;
import tech.wenisch.petri.agent.orchestration.AgentRunLauncher;
import tech.wenisch.petri.agent.orchestration.AgentChatQueueService;
import tech.wenisch.petri.agent.orchestration.AgentChatView;
import tech.wenisch.petri.agent.orchestration.AgentChatStream;
import tech.wenisch.petri.agent.orchestration.AgentChatSummaryView;
import tech.wenisch.petri.agent.orchestration.AgentRunService;
import tech.wenisch.petri.agent.orchestration.AgentRunView;
import tech.wenisch.petri.agent.orchestration.CreateAgentRunCommand;
import tech.wenisch.petri.agent.run.TestRunService;
import tech.wenisch.petri.agent.run.TestRunView;
import tech.wenisch.petri.git.ProjectWorktreeService;
import tech.wenisch.petri.git.ProjectWorktreeStatus;
import tech.wenisch.petri.git.ProjectWorktreeView;
import tech.wenisch.petri.git.GitOperationException;
import tech.wenisch.petri.project.Project;
import tech.wenisch.petri.project.ProjectRepository;
import tech.wenisch.petri.project.ProjectAccessService;
import tech.wenisch.petri.security.AppUser;
import tech.wenisch.petri.security.AppUserRepository;

@Controller
@RequestMapping("/chat")
public class ChatController {
  private final ProjectRepository projects;
  private final ProjectWorktreeService worktrees;
  private final AgentRunService runs;
  private final AgentRunLauncher launcher;
  private final AgentChatQueueService queue;
  private final AgentChatStream chatStream;
  private final ModelCredentialService modelCredentials;
  private final AppUserRepository users;
  private final ChangeProposalService changes;
  private final TestRunService testRuns;
  private final ProjectAccessService access;

  public ChatController(
      ProjectRepository projects,
      ProjectWorktreeService worktrees,
      AgentRunService runs,
      AgentRunLauncher launcher,
      AgentChatQueueService queue,
      AgentChatStream chatStream,
      ModelCredentialService modelCredentials,
      AppUserRepository users,
      ChangeProposalService changes,
      TestRunService testRuns,
      ProjectAccessService access) {
    this.projects = projects;
    this.worktrees = worktrees;
    this.runs = runs;
    this.launcher = launcher;
    this.queue = queue;
    this.chatStream = chatStream;
    this.modelCredentials = modelCredentials;
    this.users = users;
    this.changes = changes;
    this.testRuns = testRuns;
    this.access = access;
  }

  @GetMapping
  String chat(
      @RequestParam(required = false) UUID project,
      @RequestParam(required = false) UUID chat,
      @RequestParam(required = false) UUID run,
      Principal principal,
      Model model) {
    AppUser viewer = actor(principal);
    if (chat == null && run != null) {
      AgentRunView selectedRun = runs.get(run);
      chat = selectedRun.chatId();
      runs.chat(chat, viewer.getId());
    }
    AgentChatView selectedChat = chat == null ? null : runs.chat(chat, viewer.getId());
    if (selectedChat != null) {
      project = worktrees.get(selectedChat.worktreeId()).projectId();
    }
    List<Project> availableProjects = access.projects(principal.getName());
    if (project == null && !availableProjects.isEmpty()) project = availableProjects.getFirst().getId();

    if (project == null) return "redirect:/projects";
    access.requireProject(project, principal.getName());

    List<ProjectWorktreeView> activeWorktrees = project == null
        ? List.of()
        : worktrees.list(project).stream()
            .filter(value -> value.status() == ProjectWorktreeStatus.ACTIVE)
            .toList();
    UUID worktreeId = selectedChat == null
        ? (activeWorktrees.isEmpty() ? null : activeWorktrees.getFirst().id())
        : selectedChat.worktreeId();
    StringBuilder destination = new StringBuilder("redirect:/workspace/projects/").append(project);
    if (worktreeId != null) destination.append("?worktree=").append(worktreeId);
    if (selectedChat != null) destination.append(worktreeId == null ? "?" : "&")
        .append("chat=").append(selectedChat.id());
    return destination.toString();
    /*
     * The former chat page was intentionally kept below as a reference while the workspace
     * migration landed. The return above makes old URLs compatible without duplicating state.
     */
    /*
    model.addAttribute("projects", availableProjects);
    model.addAttribute("selectedProjectId", project);
    model.addAttribute("worktrees", activeWorktrees);
    model.addAttribute("selectedChat", selectedChat);
    model.addAttribute("chatRuns", selectedChat == null ? List.of() : runs.chatRuns(selectedChat.id()));
    model.addAttribute("recentChats", runs.listChats(viewer.getId()).stream().limit(40).toList());
    List<ModelCredentialStatus> configuredModels = java.util.Arrays.stream(ModelProvider.values())
        .map(modelCredentials::status)
        .filter(ModelCredentialStatus::configured)
        .toList();
    ModelCredentialStatus preferredModel = configuredModels.stream()
        .filter(value -> value.provider() == ModelProvider.OLLAMA)
        .findFirst()
        .orElseGet(() -> configuredModels.isEmpty()
            ? modelCredentials.status(ModelProvider.OLLAMA)
            : configuredModels.getFirst());
    model.addAttribute("configuredModels", configuredModels);
    model.addAttribute("modelCredential", preferredModel);
    model.addAttribute("modelConfigured", !configuredModels.isEmpty());
    model.addAttribute("reasoningEfforts", ReasoningEffort.values());
    model.addAttribute("textVerbosities", TextVerbosity.values());
    List<AgentRunView> selectedRuns = selectedChat == null ? List.of() : runs.chatRuns(selectedChat.id());
    List<AgentChatSummaryView> chatSummaries = selectedChat == null
        ? List.of() : runs.chatSummaries(selectedChat.id());
    model.addAttribute("summariesByThrough", chatSummaries.stream().collect(java.util.stream.Collectors.groupingBy(
        AgentChatSummaryView::throughMessageIndex)));
    model.addAttribute("changeProposalsByRun", selectedRuns.stream().collect(java.util.stream.Collectors.toMap(
        AgentRunView::id, value -> changes.listForAgentRun(value.id()))));
    model.addAttribute("testRunsByRun", selectedRuns.stream().collect(java.util.stream.Collectors.toMap(
        AgentRunView::id, value -> testRuns.listForAgentRun(value.id()))));
    model.addAttribute("openChangeProposals", selectedRuns.stream()
        .flatMap(value -> changes.listForAgentRun(value.id()).stream())
        .filter(value -> value.status().name().equals("PROPOSED") || value.status().name().equals("APPROVED"))
        .toList());
    model.addAttribute("openTestRuns", selectedRuns.stream()
        .flatMap(value -> testRuns.listForAgentRun(value.id()).stream())
        .filter(value -> value.status().name().equals("PROPOSED") || value.status().name().equals("APPROVED") || value.status().name().equals("RUNNING"))
        .toList());
    return "chat";
    */
  }

  @PostMapping("/worktrees")
  String createWorktree(
      @RequestParam UUID projectId,
      @RequestParam(required = false) String revision,
      Principal principal,
      RedirectAttributes redirect) {
    try {
      access.requireProject(projectId, principal.getName());
      worktrees.create(projectId, revision, principal.getName());
      redirect.addFlashAttribute("chatMessage", "Workspace prepared.");
    } catch (IllegalArgumentException | IllegalStateException | GitOperationException exception) {
      redirect.addFlashAttribute("chatError", exception.getMessage());
    }
    return "redirect:/chat?project=" + projectId;
  }

  @PostMapping("/runs")
  String createRun(
      @RequestParam UUID worktreeId,
      @RequestParam String prompt,
      @RequestParam ModelProvider provider,
      @RequestParam(defaultValue = "100") Integer maxModelTurns,
      @RequestParam(required = false) String model,
      @RequestParam(required = false) Integer maxOutputTokens,
      @RequestParam(required = false) Integer contextWindowTokens,
      @RequestParam(required = false) ReasoningEffort reasoningEffort,
      @RequestParam(required = false) TextVerbosity textVerbosity,
      Principal principal,
      RedirectAttributes redirect) {
    try {
      AppUser actor = actor(principal);
      access.requireWorktree(worktreeId, principal.getName());
      AgentRunView run = runs.create(
          worktreeId,
          new CreateAgentRunCommand(
              prompt,
              provider,
              maxModelTurns,
              model,
              maxOutputTokens,
              contextWindowTokens,
              reasoningEffort,
              textVerbosity),
          actor.getId(),
          actor.getEmail());
      launcher.launch(
          run.id(), run.worktreeId(), actor.getId(), "web:" + actor.getEmail());
      return "redirect:/chat?chat=" + run.chatId();
    } catch (IllegalArgumentException | IllegalStateException exception) {
      redirect.addFlashAttribute("chatError", exception.getMessage());
      return "redirect:/chat";
    }
  }

  @PostMapping("/{chatId}/messages")
  String enqueueMessage(
      @PathVariable UUID chatId,
      @RequestParam String prompt,
      @RequestParam ModelProvider provider,
      @RequestParam(defaultValue = "100") Integer maxModelTurns,
      @RequestParam(required = false) String model,
      @RequestParam(required = false) Integer maxOutputTokens,
      @RequestParam(required = false) Integer contextWindowTokens,
      @RequestParam(required = false) ReasoningEffort reasoningEffort,
      @RequestParam(required = false) TextVerbosity textVerbosity,
      Principal principal,
      RedirectAttributes redirect) {
    try {
      AppUser actor = actor(principal);
      runs.enqueue(chatId, new CreateAgentRunCommand(prompt, provider, maxModelTurns, model,
          maxOutputTokens, contextWindowTokens, reasoningEffort, textVerbosity), actor.getId(),
          "web:" + actor.getEmail());
      queue.resume(chatId, false);
    } catch (IllegalArgumentException | IllegalStateException exception) {
      redirect.addFlashAttribute("chatError", exception.getMessage());
    }
    return "redirect:/chat?chat=" + chatId;
  }

  @PostMapping("/{chatId}/queue/resume")
  String resumeQueue(
      @PathVariable UUID chatId,
      @RequestParam(defaultValue = "false") boolean overrideApprovals,
      Principal principal,
      RedirectAttributes redirect) {
    try {
      AppUser actor = actor(principal);
      runs.resumeChatQueue(chatId, actor.getId(), overrideApprovals);
      queue.resume(chatId, overrideApprovals);
    } catch (IllegalArgumentException | IllegalStateException exception) {
      redirect.addFlashAttribute("chatError", exception.getMessage());
    }
    return "redirect:/chat?chat=" + chatId;
  }

  @PostMapping("/runs/{runId}/resume")
  String resume(
      @PathVariable UUID runId, Principal principal, RedirectAttributes redirect) {
    UUID chatId = null;
    try {
      AppUser actor = actor(principal);
      AgentRunView run = runs.get(runId);
      chatId = run.chatId();
      runs.chat(chatId, actor.getId());
      launcher.launch(
          run.id(), run.worktreeId(), actor.getId(), "web:" + actor.getEmail());
      redirect.addFlashAttribute("chatMessage", "Run queued for execution.");
    } catch (IllegalArgumentException | IllegalStateException exception) {
      redirect.addFlashAttribute("chatError", exception.getMessage());
    }
    return chatId == null ? "redirect:/chat" : "redirect:/chat?chat=" + chatId;
  }

  @PostMapping("/runs/{runId}/cancel")
  String cancel(
      @PathVariable UUID runId, Principal principal, RedirectAttributes redirect) {
    UUID chatId = null;
    try {
      AppUser actor = actor(principal);
      chatId = runs.get(runId).chatId();
      runs.chat(chatId, actor.getId());
      runs.requestCancellation(runId, actor.getId(), actor.getEmail());
      redirect.addFlashAttribute("chatMessage", "Cancellation requested.");
    } catch (IllegalArgumentException | IllegalStateException exception) {
      redirect.addFlashAttribute("chatError", exception.getMessage());
    }
    return chatId == null ? "redirect:/chat" : "redirect:/chat?chat=" + chatId;
  }

  @GetMapping("/runs/{runId}/state")
  @ResponseBody
  ResponseEntity<AgentRunView> state(@PathVariable UUID runId, Principal principal) {
    AgentRunView run = runs.get(runId);
    runs.chat(run.chatId(), actor(principal).getId());
    return ResponseEntity.ok(run);
  }

  @GetMapping("/{chatId}/state")
  @ResponseBody
  ResponseEntity<AgentChatView> chatState(@PathVariable UUID chatId, Principal principal) {
    return ResponseEntity.ok(runs.chat(chatId, actor(principal).getId()));
  }

  @GetMapping(value = "/{chatId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  SseEmitter stream(@PathVariable UUID chatId, Principal principal) {
    return chatStream.subscribe(chatId, runs.chat(chatId, actor(principal).getId()));
  }

  @PostMapping("/runs/{runId}/changes/{proposalId}/approve")
  String approveChange(
      @PathVariable UUID runId,
      @PathVariable UUID proposalId,
      Principal principal,
      RedirectAttributes redirect) {
    return operation(runId, principal, redirect, () -> {
      requireChange(runId, proposalId);
      changes.approve(proposalId, principal.getName());
    }, "Change proposal approved.");
  }

  @PostMapping("/runs/{runId}/changes/{proposalId}/reject")
  String rejectChange(
      @PathVariable UUID runId,
      @PathVariable UUID proposalId,
      Principal principal,
      RedirectAttributes redirect) {
    return operation(runId, principal, redirect, () -> {
      requireChange(runId, proposalId);
      changes.reject(proposalId, principal.getName());
    }, "Change proposal rejected.");
  }

  @PostMapping("/runs/{runId}/changes/{proposalId}/apply")
  String applyChange(
      @PathVariable UUID runId,
      @PathVariable UUID proposalId,
      Principal principal,
      RedirectAttributes redirect) {
    return operation(runId, principal, redirect, () -> {
      requireChange(runId, proposalId);
      changes.apply(proposalId);
    }, "Approved change applied.");
  }

  @PostMapping("/runs/{runId}/tests/{testRunId}/approve")
  String approveTest(
      @PathVariable UUID runId,
      @PathVariable UUID testRunId,
      Principal principal,
      RedirectAttributes redirect) {
    return operation(runId, principal, redirect, () -> {
      requireTestRun(runId, testRunId);
      testRuns.approve(testRunId, principal.getName());
    }, "Test run approved.");
  }

  @PostMapping("/runs/{runId}/tests/{testRunId}/reject")
  String rejectTest(
      @PathVariable UUID runId,
      @PathVariable UUID testRunId,
      Principal principal,
      RedirectAttributes redirect) {
    return operation(runId, principal, redirect, () -> {
      requireTestRun(runId, testRunId);
      testRuns.reject(testRunId, principal.getName());
    }, "Test run rejected.");
  }

  @PostMapping("/runs/{runId}/tests/{testRunId}/execute")
  String executeTest(
      @PathVariable UUID runId,
      @PathVariable UUID testRunId,
      Principal principal,
      RedirectAttributes redirect) {
    return operation(runId, principal, redirect, () -> {
      requireTestRun(runId, testRunId);
      testRuns.execute(testRunId);
    }, "Approved test run executed.");
  }

  private AppUser actor(Principal principal) {
    return users.findByEmailIgnoreCase(principal.getName())
        .orElseThrow(() -> new IllegalArgumentException("Unknown user"));
  }

  private void requireChange(UUID runId, UUID proposalId) {
    AgentRunView run = runs.get(runId);
    ChangeProposalView proposal = changes.get(proposalId, run.worktreeId());
    if (!runId.equals(proposal.agentRunId())) {
      throw new IllegalArgumentException("Change proposal belongs to another agent run");
    }
  }

  private void requireTestRun(UUID runId, UUID testRunId) {
    AgentRunView run = runs.get(runId);
    TestRunView testRun = testRuns.get(testRunId, run.worktreeId());
    if (!runId.equals(testRun.agentRunId())) {
      throw new IllegalArgumentException("Test run belongs to another agent run");
    }
  }

  private String operation(
      UUID runId, Principal principal, RedirectAttributes redirect,
      Runnable action, String successMessage) {
    UUID chatId = runs.get(runId).chatId();
    try {
      runs.chat(chatId, actor(principal).getId());
      action.run();
      queue.approvalsChanged(chatId);
      redirect.addFlashAttribute("chatMessage", successMessage);
    } catch (IllegalArgumentException | IllegalStateException exception) {
      redirect.addFlashAttribute("chatError", exception.getMessage());
    }
    return "redirect:/chat?chat=" + chatId;
  }
}
