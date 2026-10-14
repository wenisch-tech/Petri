package tech.wenisch.petri.web;

import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import tech.wenisch.petri.git.ProjectWorktreeService;
import tech.wenisch.petri.git.ProjectWorktreeView;
import tech.wenisch.petri.project.Project;
import tech.wenisch.petri.project.ProjectAccessService;
import tech.wenisch.petri.vulnerability.VulnerabilityReportService;

/** The latest vulnerability scan of a project's branch, as a table with the full descriptions. */
@Controller
public class ProjectVulnerabilitiesController {
  private static final List<String> SEVERITIES = List.of("CRITICAL", "HIGH", "MEDIUM", "LOW", "INFO");

  private final ProjectAccessService access;
  private final ProjectWorktreeService worktrees;
  private final VulnerabilityReportService reports;

  public ProjectVulnerabilitiesController(
      ProjectAccessService access, ProjectWorktreeService worktrees,
      VulnerabilityReportService reports) {
    this.access = access;
    this.worktrees = worktrees;
    this.reports = reports;
  }

  @GetMapping("/projects/{projectId}/vulnerabilities")
  String vulnerabilities(
      @PathVariable UUID projectId,
      @RequestParam(required = false) UUID worktree,
      @RequestParam(defaultValue = "false") boolean embedded,
      Principal principal,
      Model model) {
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
    model.addAttribute("project", project);
    model.addAttribute("branch", branch);
    model.addAttribute("severities", SEVERITIES);
    model.addAttribute("report", reports.find(projectId, branch, revision).orElse(null));
    return embedded ? "project-vulnerabilities :: content" : "project-vulnerabilities";
  }
}
