package tech.wenisch.petri.web;

import java.security.Principal;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import tech.wenisch.petri.agent.change.ChangeProposalService;
import tech.wenisch.petri.agent.change.ChangeProposalView;
import tech.wenisch.petri.agent.orchestration.AgentRunService;
import tech.wenisch.petri.agent.orchestration.AgentRunView;
import tech.wenisch.petri.agent.run.TestRunService;
import tech.wenisch.petri.agent.run.TestRunView;
import tech.wenisch.petri.security.AppUserRepository;

@Controller
@RequestMapping("/runs")
public class AgentRunAdminController {
  private final AgentRunService runs;
  private final ChangeProposalService changes;
  private final TestRunService testRuns;
  private final AppUserRepository users;

  public AgentRunAdminController(
      AgentRunService runs,
      ChangeProposalService changes,
      TestRunService testRuns,
      AppUserRepository users) {
    this.runs = runs;
    this.changes = changes;
    this.testRuns = testRuns;
    this.users = users;
  }

  @GetMapping
  String runs(
      @RequestParam(defaultValue = "false") boolean includeWorkflowStages,
      Model model, Principal principal) {
    model.addAttribute("runs", runs.listAll(actorId(principal), includeWorkflowStages));
    model.addAttribute("includeWorkflowStages", includeWorkflowStages);
    return "runs";
  }

  @GetMapping("/{runId}")
  String run(@PathVariable UUID runId, Principal principal, Model model) {
    AgentRunView run = runs.get(runId);
    runs.chat(run.chatId(), actorId(principal));
    model.addAttribute("run", run);
    model.addAttribute("changeProposals", changes.listForAgentRun(runId));
    model.addAttribute("testRuns", testRuns.listForAgentRun(runId));
    return "run-detail";
  }

  @PostMapping("/{runId}/changes/{proposalId}/approve")
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

  @PostMapping("/{runId}/changes/{proposalId}/reject")
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

  @PostMapping("/{runId}/changes/{proposalId}/apply")
  String applyChange(
      @PathVariable UUID runId,
      @PathVariable UUID proposalId,
      Principal principal,
      RedirectAttributes redirect) {
    return operation(runId, principal, redirect, () -> {
      requireChange(runId, proposalId);
      changes.apply(proposalId);
    }, "Approved change proposal applied.");
  }

  @PostMapping("/{runId}/tests/{testRunId}/approve")
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

  @PostMapping("/{runId}/tests/{testRunId}/reject")
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

  @PostMapping("/{runId}/tests/{testRunId}/execute")
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
    try {
      runs.chat(runs.get(runId).chatId(), actorId(principal));
      action.run();
      redirect.addFlashAttribute("operationMessage", successMessage);
    } catch (IllegalArgumentException | IllegalStateException exception) {
      redirect.addFlashAttribute("operationError", exception.getMessage());
    }
    return "redirect:/runs/" + runId;
  }

  private UUID actorId(Principal principal) {
    return users.findByEmailIgnoreCase(principal.getName())
        .orElseThrow(() -> new IllegalArgumentException("Unknown user"))
        .getId();
  }
}
