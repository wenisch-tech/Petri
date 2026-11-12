package tech.wenisch.petri.web;

import java.security.Principal;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import tech.wenisch.petri.git.ProjectWorktreeService;
import tech.wenisch.petri.git.ProjectWorktreeStatus;
import tech.wenisch.petri.kubernetes.ClusterConfig;
import tech.wenisch.petri.kubernetes.ClusterGateway;
import tech.wenisch.petri.kubernetes.ConfiguredWorkspaceHostResolver;
import tech.wenisch.petri.kubernetes.KubernetesSettingsService;
import tech.wenisch.petri.kubernetes.KubernetesWorkspaceConfig;
import tech.wenisch.petri.kubernetes.WorkspacePodOrchestrator;
import tech.wenisch.petri.project.ProjectAccessService;

/** The Kubernetes workspace of one project: switch, cluster, pod settings and the pod itself. */
@Controller
public class ProjectKubernetesController {
  private static final Logger log = LoggerFactory.getLogger(ProjectKubernetesController.class);

  private final ProjectAccessService access;
  private final KubernetesSettingsService settings;
  private final WorkspacePodOrchestrator orchestrator;
  private final ClusterGateway gateway;
  private final ProjectWorktreeService worktrees;
  private final ConfiguredWorkspaceHostResolver resolver;

  public ProjectKubernetesController(
      ProjectAccessService access, KubernetesSettingsService settings,
      WorkspacePodOrchestrator orchestrator, ClusterGateway gateway,
      ProjectWorktreeService worktrees, ConfiguredWorkspaceHostResolver resolver) {
    this.access = access;
    this.settings = settings;
    this.orchestrator = orchestrator;
    this.gateway = gateway;
    this.worktrees = worktrees;
    this.resolver = resolver;
  }

  @GetMapping("/projects/{projectId}/settings/kubernetes")
  String page(
      @PathVariable UUID projectId,
      @RequestParam(defaultValue = "false") boolean embedded,
      Principal principal,
      Model model) {
    model.addAttribute("project", access.requireProject(projectId, principal.getName()));
    KubernetesWorkspaceConfig instance = settings.instance();
    model.addAttribute("cfg", settings.project(projectId)
        .orElseGet(() -> settings.blankProject(projectId)));
    model.addAttribute("instance", instance);
    model.addAttribute("instanceReady", settings.clusterFor(null) != null);
    model.addAttribute("state", orchestrator.status(projectId).orElse(null));
    return embedded ? "project-kubernetes :: content" : "project-kubernetes";
  }

  @PostMapping("/projects/{projectId}/settings/kubernetes")
  String save(
      @PathVariable UUID projectId, @ModelAttribute KubernetesForm form, Principal principal,
      RedirectAttributes redirect) {
    access.requireProject(projectId, principal.getName());
    boolean wasEnabled = settings.enabled(projectId);
    if (wasEnabled != Boolean.TRUE.equals(form.enabled()) && hasActiveWorktrees(projectId)) {
      redirect.addFlashAttribute("error", "Remove this project's branch worktrees first: the "
          + "workspace moves to a different host, so the repository is cloned again there.");
      return back(projectId);
    }
    try {
      settings.saveProject(projectId, form.toSave(), principal.getName());
      resolver.invalidate(projectId);
      redirect.addFlashAttribute("notice", "Kubernetes workspace settings saved");
    } catch (IllegalArgumentException exception) {
      redirect.addFlashAttribute("error", exception.getMessage());
    }
    return back(projectId);
  }

  private boolean hasActiveWorktrees(UUID projectId) {
    return worktrees.list(projectId).stream()
        .anyMatch(value -> value.status() == ProjectWorktreeStatus.ACTIVE);
  }

  @PostMapping("/projects/{projectId}/settings/kubernetes/test")
  String test(@PathVariable UUID projectId, Principal principal, RedirectAttributes redirect) {
    access.requireProject(projectId, principal.getName());
    try {
      var effective = settings.effective(projectId);
      var result = gateway.check(effective.cluster(), effective.pod().namespace());
      redirect.addFlashAttribute(result.ok() ? "notice" : "error", result.message());
    } catch (IllegalStateException exception) {
      redirect.addFlashAttribute("error", exception.getMessage());
    }
    return back(projectId);
  }

  @PostMapping("/projects/{projectId}/settings/kubernetes/start")
  String start(@PathVariable UUID projectId, Principal principal, RedirectAttributes redirect) {
    access.requireProject(projectId, principal.getName());
    if (!settings.enabled(projectId)) {
      redirect.addFlashAttribute("error", "Turn on the Kubernetes workspace first");
      return back(projectId);
    }
    // Starting waits for the image pull and the agent; the page shows the phase meanwhile.
    Thread.startVirtualThread(() -> {
      try {
        orchestrator.ensureRunning(projectId);
      } catch (RuntimeException exception) {
        log.warn("Workspace pod of project {} did not start: {}", projectId, exception.getMessage());
      }
    });
    redirect.addFlashAttribute("notice", "Starting the workspace pod. Reload this page to follow it.");
    return back(projectId);
  }

  @PostMapping("/projects/{projectId}/settings/kubernetes/stop")
  String stop(@PathVariable UUID projectId, Principal principal, RedirectAttributes redirect) {
    access.requireProject(projectId, principal.getName());
    try {
      orchestrator.stop(projectId, principal.getName());
      redirect.addFlashAttribute("notice", "The workspace pod was stopped. Its volume is kept.");
    } catch (RuntimeException exception) {
      redirect.addFlashAttribute("error", exception.getMessage());
    }
    return back(projectId);
  }

  @PostMapping("/projects/{projectId}/settings/kubernetes/delete-data")
  String deleteData(
      @PathVariable UUID projectId, @RequestParam(defaultValue = "false") boolean confirm,
      Principal principal, RedirectAttributes redirect) {
    access.requireProject(projectId, principal.getName());
    if (settings.enabled(projectId)) {
      redirect.addFlashAttribute("error", "Turn off the Kubernetes workspace before deleting its data");
    } else if (!confirm) {
      redirect.addFlashAttribute("error", "Confirm that the workspace data should be deleted");
    } else {
      try {
        orchestrator.deleteData(projectId, principal.getName());
        redirect.addFlashAttribute("notice", "The workspace pod, volume and credentials were deleted.");
      } catch (RuntimeException exception) {
        redirect.addFlashAttribute("error", exception.getMessage());
      }
    }
    return back(projectId);
  }

  /** Small status for the workspace page. */
  @GetMapping("/workspace/api/projects/{projectId}/kubernetes/status")
  @ResponseBody
  ResponseEntity<Map<String, Object>> status(@PathVariable UUID projectId, Principal principal) {
    access.requireProject(projectId, principal.getName());
    if (!settings.enabled(projectId)) return ResponseEntity.ok(Map.of("enabled", false));
    var state = orchestrator.status(projectId);
    return ResponseEntity.ok(Map.of(
        "enabled", true,
        "phase", state.map(value -> value.getPhase().name()).orElse("STOPPED"),
        "image", state.map(value -> value.getImage() == null ? "" : value.getImage()).orElse(""),
        "error", state.map(value -> value.getLastError() == null ? "" : value.getLastError()).orElse("")));
  }

  private static String back(UUID projectId) {
    return "redirect:/projects/" + projectId + "/settings/kubernetes";
  }
}
