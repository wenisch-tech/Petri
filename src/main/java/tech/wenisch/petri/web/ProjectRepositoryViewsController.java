package tech.wenisch.petri.web;

import java.security.Principal;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import tech.wenisch.petri.agent.orchestration.AgentRunService;
import tech.wenisch.petri.agent.orchestration.AgentWorkflowService;
import tech.wenisch.petri.automation.AutomationKind;
import tech.wenisch.petri.automation.AutomationRun;
import tech.wenisch.petri.automation.AutomationPostMode;
import tech.wenisch.petri.automation.AutomationRunService;
import tech.wenisch.petri.automation.AutomationRunStatus;
import tech.wenisch.petri.automation.AutomationScheduler;
import tech.wenisch.petri.git.provider.ProjectRepositoryActivityService;
import tech.wenisch.petri.project.ProjectAccessService;

/** The workspace's Pull requests and Pipelines views of a project's remote repository. */
@Controller
public class ProjectRepositoryViewsController {
  private final ProjectAccessService access;
  private final ProjectRepositoryActivityService activity;
  private final AutomationScheduler scheduler;
  private final AutomationRunService automationRuns;
  private final AgentRunService agentRuns;
  private final AgentWorkflowService workflows;

  public ProjectRepositoryViewsController(
      ProjectAccessService access, ProjectRepositoryActivityService activity,
      AutomationScheduler scheduler, AutomationRunService automationRuns,
      AgentRunService agentRuns, AgentWorkflowService workflows) {
    this.agentRuns = agentRuns;
    this.workflows = workflows;
    this.access = access;
    this.activity = activity;
    this.scheduler = scheduler;
    this.automationRuns = automationRuns;
  }

  @GetMapping("/projects/{projectId}/pull-requests")
  String pullRequests(
      @PathVariable UUID projectId,
      @RequestParam(defaultValue = "open") String state,
      @RequestParam(defaultValue = "false") boolean embedded,
      Principal principal,
      Model model) {
    model.addAttribute("project", access.requireProject(projectId, principal.getName()));
    model.addAttribute("view", activity.pullRequests(projectId, principal.getName(), state));
    return embedded ? "project-pull-requests :: content" : "project-pull-requests";
  }

  @GetMapping("/projects/{projectId}/pull-requests/{number}")
  String pullRequest(
      @PathVariable UUID projectId,
      @PathVariable long number,
      @RequestParam(defaultValue = "false") boolean embedded,
      Principal principal,
      Model model) {
    model.addAttribute("project", access.requireProject(projectId, principal.getName()));
    model.addAttribute("view", activity.pullRequest(projectId, principal.getName(), number));
    model.addAttribute("number", number);
    var petriRuns = automationRuns.recent(projectId).stream()
        .filter(run -> Long.toString(number).equals(run.getExternalRef()))
        .limit(6).toList();
    model.addAttribute("petriRuns", petriRuns);
    model.addAttribute("petriRunsLive", petriRuns.stream().anyMatch(run ->
        run.getStatus().active() || run.getStatus() == AutomationRunStatus.IMPROVING));
    model.addAttribute("petriKinds", AutomationKind.values());
    return embedded ? "project-pull-request :: content" : "project-pull-request";
  }

  @PostMapping("/projects/{projectId}/pull-requests/{number}/actions")
  String pullRequestAction(
      @PathVariable UUID projectId,
      @PathVariable long number,
      @RequestParam String action,
      @RequestParam(defaultValue = "") String body,
      Principal principal,
      RedirectAttributes redirect) {
    var result = activity.act(projectId, principal.getName(), number, action, body);
    redirect.addFlashAttribute(result.ok() ? "prNotice" : "prError", result.message());
    boolean discussion = "comment".equals(action) || "approve".equals(action)
        || "request_changes".equals(action);
    redirect.addFlashAttribute("prTab", discussion ? "comments" : "overview");
    if (!result.ok() && "comment".equals(action)) redirect.addFlashAttribute("prDraft", body);
    return "redirect:/projects/" + projectId + "/pull-requests/" + number;
  }

  /** Starts one of the review automations on this pull request right now. */
  @PostMapping("/projects/{projectId}/pull-requests/{number}/automations")
  String runAutomation(
      @PathVariable UUID projectId,
      @PathVariable long number,
      @RequestParam AutomationKind kind,
      Principal principal,
      RedirectAttributes redirect) {
    access.requireProject(projectId, principal.getName());
    try {
      UUID runId = scheduler.runOnDemand(projectId, kind, number, AutomationPostMode.AUTO);
      redirect.addFlashAttribute("prNotice", kind.title() + " started on #" + number
          + ". Its output is followed in the chat on the right.");
      redirect.addFlashAttribute("prFollowRun", runId.toString());
    } catch (RuntimeException exception) {
      redirect.addFlashAttribute("prError", exception.getMessage());
    }
    redirect.addFlashAttribute("prTab", "overview");
    return "redirect:/projects/" + projectId + "/pull-requests/" + number;
  }

  /** Where to follow an automation run live: the chat that holds its agent run or fix workflow. */
  public record FollowInfo(
      String status, boolean active, String chatId, String mode, String label) {}

  @GetMapping(value = "/projects/{projectId}/automations/runs/{runId}/follow",
      produces = "application/json")
  @ResponseBody
  FollowInfo follow(
      @PathVariable UUID projectId, @PathVariable UUID runId, Principal principal) {
    var project = access.requireProject(projectId, principal.getName());
    AutomationRun run;
    try {
      run = automationRuns.require(runId);
    } catch (IllegalArgumentException exception) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.NOT_FOUND);
    }
    if (!run.getProjectId().equals(projectId)) {
      throw new org.springframework.web.server.ResponseStatusException(
          org.springframework.http.HttpStatus.NOT_FOUND);
    }
    boolean active = run.getStatus().active() || run.getStatus() == AutomationRunStatus.IMPROVING;
    String label = run.getKind().title() + " · #" + run.getExternalRef();
    String chatId = null;
    String mode = "DIRECT";
    try {
      if (run.getImproveWorkflowId() != null) {
        var workflow = workflows.get(run.getImproveWorkflowId(), project.getCreatedBy());
        chatId = workflow.chatId() == null ? null : workflow.chatId().toString();
        mode = "ORCHESTRATED";
        label = "Fix · #" + run.getExternalRef();
      }
      if (chatId == null && run.getAgentRunId() != null) {
        chatId = agentRuns.get(run.getAgentRunId()).chatId().toString();
        mode = "DIRECT";
      }
    } catch (RuntimeException exception) {
      chatId = null;
    }
    return new FollowInfo(run.getStatus().name(), active, chatId, mode, label);
  }

  @GetMapping("/projects/{projectId}/pipelines")
  String pipelines(
      @PathVariable UUID projectId,
      @RequestParam(defaultValue = "false") boolean embedded,
      Principal principal,
      Model model) {
    model.addAttribute("project", access.requireProject(projectId, principal.getName()));
    model.addAttribute("view", activity.pipelines(projectId, principal.getName()));
    return embedded ? "project-pipelines :: content" : "project-pipelines";
  }
}
