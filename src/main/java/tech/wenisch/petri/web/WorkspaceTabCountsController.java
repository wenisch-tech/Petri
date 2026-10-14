package tech.wenisch.petri.web;

import java.security.Principal;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tech.wenisch.petri.automation.AutomationSettingsService;
import tech.wenisch.petri.automation.ProjectAutomation;
import tech.wenisch.petri.git.ProjectWorktreeView;
import tech.wenisch.petri.git.provider.ProjectRepositoryActivityService;
import tech.wenisch.petri.project.Project;
import tech.wenisch.petri.project.ProjectAccessService;
import tech.wenisch.petri.vulnerability.VulnerabilityReportService;
import tech.wenisch.petri.vulnerability.VulnerabilityReportService.Summary;

/** The numbers shown on the workspace's view tabs: open pull requests, findings, automations. */
@RestController
@RequestMapping("/workspace/api")
public class WorkspaceTabCountsController {
  /** Null values mean "not known": no provider connection, or the provider could not be asked. */
  public record TabCounts(
      Integer openPullRequests, Summary vulnerabilities, int automations, int automationsEnabled) {}

  private final ProjectAccessService access;
  private final ProjectRepositoryActivityService activity;
  private final VulnerabilityReportService vulnerabilities;
  private final AutomationSettingsService automations;

  public WorkspaceTabCountsController(
      ProjectAccessService access, ProjectRepositoryActivityService activity,
      VulnerabilityReportService vulnerabilities, AutomationSettingsService automations) {
    this.access = access;
    this.activity = activity;
    this.vulnerabilities = vulnerabilities;
    this.automations = automations;
  }

  @GetMapping("/projects/{projectId}/tab-counts")
  public TabCounts counts(
      @PathVariable UUID projectId,
      @RequestParam(required = false) UUID worktree,
      Principal principal) {
    Project project = access.requireProject(projectId, principal.getName());
    String branch = project.getDefaultBranch();
    String revision = null;
    if (worktree != null) {
      ProjectWorktreeView view = access.requireWorktree(worktree, principal.getName());
      if (view.projectId().equals(projectId)) {
        branch = view.branchName();
        revision = view.gitRevision();
      }
    }
    Integer open = null;
    try {
      var pulls = activity.pullRequests(projectId, principal.getName(), "open");
      if (pulls.unavailable() == null && pulls.error() == null) open = pulls.items().size();
    } catch (RuntimeException exception) {
      open = null;
    }
    Summary scan = vulnerabilities.find(projectId, branch, revision)
        .map(VulnerabilityReportService::summary).orElse(null);
    var configured = automations.list(projectId, principal.getName());
    int enabled = (int) configured.stream().filter(ProjectAutomation::isEnabled).count();
    return new TabCounts(open, scan, configured.size(), enabled);
  }
}
