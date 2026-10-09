package tech.wenisch.petri.web;

import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import tech.wenisch.petri.agent.change.ChangeProposalService;
import tech.wenisch.petri.agent.change.ChangeProposalView;
import tech.wenisch.petri.agent.model.ModelCredentialService;
import tech.wenisch.petri.agent.model.ModelCredentialStatus;
import tech.wenisch.petri.agent.model.ModelProvider;
import tech.wenisch.petri.agent.model.ReasoningEffort;
import tech.wenisch.petri.agent.model.TextVerbosity;
import tech.wenisch.petri.agent.orchestration.AgentRunLauncher;
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
import tech.wenisch.petri.security.AppUser;
import tech.wenisch.petri.security.AppUserRepository;

@Controller
@RequestMapping("/chat")
public class ChatController {
  private final ProjectRepository projects;
  private final ProjectWorktreeService worktrees;
  private final AgentRunService runs;
  private final AgentRunLauncher launcher;
  private final ModelCredentialService modelCredentials;
  private final AppUserRepository users;
  private final ChangeProposalService changes;
  private final TestRunService testRuns;

  public ChatController(
      ProjectRepository projects,
      ProjectWorktreeService worktrees,
      AgentRunService runs,
      AgentRunLauncher launcher,
      ModelCredentialService modelCredentials,
      AppUserRepository users,
      ChangeProposalService changes,
      TestRunService testRuns) {
    this.projects = projects;
    this.worktrees = worktrees;
    this.runs = runs;
    this.launcher = launcher;
    this.modelCredentials = modelCredentials;
    this.users = users;
    this.changes = changes;
    this.testRuns = testRuns;
  }

  @GetMapping
  String chat(
      @RequestParam(required = false) UUID project,
      @RequestParam(required = false) UUID run,
      Model model) {
    AgentRunView selectedRun = run == null ? null : runs.get(run);
    if (selectedRun != null) {
      project = worktrees.get(selectedRun.worktreeId()).projectId();
    }
    List<Project> availableProjects = projects.findAllByOrderByCreatedAtDesc();
    if (project == null && !availableProjects.isEmpty()) project = availableProjects.getFirst().getId();

    List<ProjectWorktreeView> activeWorktrees = project == null
        ? List.of()
        : worktrees.list(project).stream()
            .filter(value -> value.status() == ProjectWorktreeStatus.ACTIVE)
            .toList();
    model.addAttribute("projects", availableProjects);
    model.addAttribute("selectedProjectId", project);
    model.addAttribute("worktrees", activeWorktrees);
    model.addAttribute("selectedRun", selectedRun);
    model.addAttribute("recentRuns", runs.listAll().stream().limit(40).toList());
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
    if (selectedRun != null) {
      model.addAttribute("changeProposals", changes.listForAgentRun(selectedRun.id()));
      model.addAttribute("testRuns", testRuns.listForAgentRun(selectedRun.id()));
    } else {
      model.addAttribute("changeProposals", List.of());
      model.addAttribute("testRuns", List.of());
    }
    return "chat";
  }

  @PostMapping("/worktrees")
  String createWorktree(
      @RequestParam UUID projectId,
      @RequestParam(required = false) String revision,
      Principal principal,
      RedirectAttributes redirect) {
    try {
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
      @RequestParam(defaultValue = "20") Integer maxModelTurns,
      @RequestParam(required = false) String model,
      @RequestParam(required = false) Integer maxOutputTokens,
      @RequestParam(required = false) ReasoningEffort reasoningEffort,
      @RequestParam(required = false) TextVerbosity textVerbosity,
      Principal principal,
      RedirectAttributes redirect) {
    try {
      AppUser actor = actor(principal);
      AgentRunView run = runs.create(
          worktreeId,
          new CreateAgentRunCommand(
              prompt,
              provider,
              maxModelTurns,
              model,
              maxOutputTokens,
              reasoningEffort,
              textVerbosity),
          actor.getId(),
          actor.getEmail());
      launcher.launch(
          run.id(), run.worktreeId(), actor.getId(), "web:" + actor.getEmail());
      return "redirect:/chat?run=" + run.id();
    } catch (IllegalArgumentException | IllegalStateException exception) {
      redirect.addFlashAttribute("chatError", exception.getMessage());
      return "redirect:/chat";
    }
  }

  @PostMapping("/runs/{runId}/resume")
  String resume(
      @PathVariable UUID runId, Principal principal, RedirectAttributes redirect) {
    try {
      AppUser actor = actor(principal);
      AgentRunView run = runs.get(runId);
      launcher.launch(
          run.id(), run.worktreeId(), actor.getId(), "web:" + actor.getEmail());
      redirect.addFlashAttribute("chatMessage", "Run queued for execution.");
    } catch (IllegalArgumentException | IllegalStateException exception) {
      redirect.addFlashAttribute("chatError", exception.getMessage());
    }
    return "redirect:/chat?run=" + runId;
  }

  @PostMapping("/runs/{runId}/cancel")
  String cancel(
      @PathVariable UUID runId, Principal principal, RedirectAttributes redirect) {
    try {
      AppUser actor = actor(principal);
      runs.requestCancellation(runId, actor.getId(), actor.getEmail());
      redirect.addFlashAttribute("chatMessage", "Cancellation requested.");
    } catch (IllegalArgumentException | IllegalStateException exception) {
      redirect.addFlashAttribute("chatError", exception.getMessage());
    }
    return "redirect:/chat?run=" + runId;
  }

  @GetMapping("/runs/{runId}/state")
  @ResponseBody
  ResponseEntity<AgentRunView> state(@PathVariable UUID runId) {
    return ResponseEntity.ok(runs.get(runId));
  }

  @PostMapping("/runs/{runId}/changes/{proposalId}/approve")
  String approveChange(
      @PathVariable UUID runId,
      @PathVariable UUID proposalId,
      Principal principal,
      RedirectAttributes redirect) {
    return operation(runId, redirect, () -> {
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
    return operation(runId, redirect, () -> {
      requireChange(runId, proposalId);
      changes.reject(proposalId, principal.getName());
    }, "Change proposal rejected.");
  }

  @PostMapping("/runs/{runId}/changes/{proposalId}/apply")
  String applyChange(
      @PathVariable UUID runId,
      @PathVariable UUID proposalId,
      RedirectAttributes redirect) {
    return operation(runId, redirect, () -> {
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
    return operation(runId, redirect, () -> {
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
    return operation(runId, redirect, () -> {
      requireTestRun(runId, testRunId);
      testRuns.reject(testRunId, principal.getName());
    }, "Test run rejected.");
  }

  @PostMapping("/runs/{runId}/tests/{testRunId}/execute")
  String executeTest(
      @PathVariable UUID runId,
      @PathVariable UUID testRunId,
      RedirectAttributes redirect) {
    return operation(runId, redirect, () -> {
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

  private static String operation(
      UUID runId, RedirectAttributes redirect, Runnable action, String successMessage) {
    try {
      action.run();
      redirect.addFlashAttribute("chatMessage", successMessage);
    } catch (IllegalArgumentException | IllegalStateException exception) {
      redirect.addFlashAttribute("chatError", exception.getMessage());
    }
    return "redirect:/chat?run=" + runId;
  }
}
