package tech.wenisch.petri.web;

import java.security.Principal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import tech.wenisch.petri.agent.model.ModelCredentialService;
import tech.wenisch.petri.automation.AutomationKind;
import tech.wenisch.petri.automation.AutomationRun;
import tech.wenisch.petri.automation.AutomationRunService;
import tech.wenisch.petri.automation.AutomationRunStatus;
import tech.wenisch.petri.automation.AutomationScheduler;
import tech.wenisch.petri.automation.AutomationSettingsService;
import tech.wenisch.petri.automation.AutomationTrigger;
import tech.wenisch.petri.automation.CodeReviewAutomation;
import tech.wenisch.petri.automation.ProjectAutomation;
import tech.wenisch.petri.project.Project;
import tech.wenisch.petri.project.ProjectAccessService;

@Controller
public class AutomationController {
  private final AutomationSettingsService settings;
  private final AutomationRunService runs;
  private final AutomationScheduler scheduler;
  private final CodeReviewAutomation review;
  private final ProjectAccessService access;
  private final ModelCredentialService models;

  public AutomationController(
      AutomationSettingsService settings, AutomationRunService runs, AutomationScheduler scheduler,
      CodeReviewAutomation review, ProjectAccessService access, ModelCredentialService models) {
    this.settings = settings;
    this.runs = runs;
    this.scheduler = scheduler;
    this.review = review;
    this.access = access;
    this.models = models;
  }

  @GetMapping("/projects/{projectId}/automations")
  String automations(
      @PathVariable UUID projectId,
      @RequestParam(defaultValue = "false") boolean embedded,
      Principal principal,
      Model model) {
    Project project = access.requireProject(projectId, principal.getName());
    List<ProjectAutomation> automations = settings.list(projectId, principal.getName());
    Instant now = Instant.now();
    Map<UUID, Instant> nextFires = new HashMap<>();
    automations.stream()
        .filter(value -> value.getTrigger() == AutomationTrigger.CRON)
        .forEach(value -> nextFires.put(value.getId(),
            AutomationSettingsService.nextFire(value.getCronExpression(), now)));
    model.addAttribute("project", project);
    model.addAttribute("automations", automations);
    model.addAttribute("nextFires", nextFires);
    model.addAttribute("kinds", AutomationKind.values());
    model.addAttribute("triggers", AutomationTrigger.values());
    model.addAttribute("unavailable", settings.unavailableReason(projectId));
    model.addAttribute("providers", models.usable(project.getCreatedBy()));
    model.addAttribute("runs", runs.recent(projectId));
    return embedded ? "project-automations :: content" : "project-automations";
  }

  @PostMapping("/projects/{projectId}/automations")
  String save(
      @PathVariable UUID projectId,
      @RequestParam(required = false) UUID automationId,
      @RequestParam(required = false) AutomationKind kind,
      @RequestParam(defaultValue = "false") boolean enabled,
      @RequestParam AutomationTrigger trigger,
      @RequestParam(required = false) String cronExpression,
      @RequestParam(defaultValue = "false") boolean completeWithoutApproval,
      @RequestParam(required = false) UUID providerInstanceId,
      @RequestParam(required = false) String model,
      @RequestParam(defaultValue = "20") int maxModelTurns,
      Principal principal,
      RedirectAttributes redirect) {
    try {
      settings.save(projectId, automationId, kind, enabled, trigger, cronExpression,
          completeWithoutApproval, providerInstanceId, model, maxModelTurns, principal.getName());
      redirect.addFlashAttribute("notice",
          automationId == null ? "Automation added" : "Automation saved");
    } catch (IllegalArgumentException exception) {
      redirect.addFlashAttribute("error", exception.getMessage());
    }
    return "redirect:/projects/" + projectId + "/automations";
  }

  @PostMapping("/projects/{projectId}/automations/{automationId}/delete")
  String delete(
      @PathVariable UUID projectId, @PathVariable UUID automationId, Principal principal,
      RedirectAttributes redirect) {
    try {
      settings.delete(projectId, automationId, principal.getName());
      redirect.addFlashAttribute("notice", "Automation removed");
    } catch (IllegalArgumentException exception) {
      redirect.addFlashAttribute("error", exception.getMessage());
    }
    return "redirect:/projects/" + projectId + "/automations";
  }

  @PostMapping("/projects/{projectId}/automations/{automationId}/run-now")
  String runNow(
      @PathVariable UUID projectId, @PathVariable UUID automationId, Principal principal,
      RedirectAttributes redirect) {
    try {
      ProjectAutomation automation = settings.require(projectId, automationId, principal.getName());
      if (settings.unavailableReason(projectId) != null) {
        throw new IllegalStateException(settings.unavailableReason(projectId));
      }
      int queued = scheduler.runNow(automation);
      redirect.addFlashAttribute("notice", queued == 0
          ? "No open pull requests need a review"
          : queued + " review(s) queued");
    } catch (RuntimeException exception) {
      redirect.addFlashAttribute("error", exception.getMessage());
    }
    return "redirect:/projects/" + projectId + "/automations";
  }

  @PostMapping("/projects/{projectId}/automations/runs/{runId}/retry")
  String retry(
      @PathVariable UUID projectId, @PathVariable UUID runId, Principal principal,
      RedirectAttributes redirect) {
    access.requireProject(projectId, principal.getName());
    AutomationRun run;
    try {
      run = runs.require(runId);
    } catch (IllegalArgumentException exception) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    if (!run.getProjectId().equals(projectId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    try {
      if (run.getStatus() != AutomationRunStatus.FAILED && run.getStatus() != AutomationRunStatus.SKIPPED) {
        throw new IllegalStateException("Only failed or skipped reviews can be retried");
      }
      List<ProjectAutomation> all = settings.list(projectId, principal.getName());
      ProjectAutomation automation = all.stream().filter(value -> value.getKind() == run.getKind())
          .sorted(java.util.Comparator.comparing(ProjectAutomation::isEnabled).reversed())
          .findFirst().orElseThrow(() -> new IllegalStateException(
              "No automation of this kind is configured for the project any more"));
      scheduler.retry(automation, run);
      redirect.addFlashAttribute("notice", "Review of #" + run.getExternalRef() + " queued again");
    } catch (RuntimeException exception) {
      redirect.addFlashAttribute("error", exception.getMessage());
    }
    return "redirect:/projects/" + projectId + "/automations";
  }

  @PostMapping("/projects/{projectId}/automations/runs/{runId}/approve")
  String approve(
      @PathVariable UUID projectId, @PathVariable UUID runId, Principal principal,
      RedirectAttributes redirect) {
    access.requireProject(projectId, principal.getName());
    AutomationRun run;
    try {
      run = runs.require(runId);
    } catch (IllegalArgumentException exception) {
      throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    }
    if (!run.getProjectId().equals(projectId)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
    if (run.getStatus() != AutomationRunStatus.AWAITING_APPROVAL) {
      redirect.addFlashAttribute("error", "This review is not waiting for approval");
    } else {
      review.publish(runId, principal.getName());
      AutomationRun updated = runs.require(runId);
      if (updated.getStatus() == AutomationRunStatus.POSTED) {
        redirect.addFlashAttribute("notice", "Review posted");
      } else {
        redirect.addFlashAttribute("error", updated.getError());
      }
    }
    return "redirect:/projects/" + projectId + "/automations";
  }
}
