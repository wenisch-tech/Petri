package tech.wenisch.petri.web;

import java.security.Principal;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import tech.wenisch.petri.agent.orchestration.AgentRunService;
import tech.wenisch.petri.project.Project;
import tech.wenisch.petri.project.ProjectAccessService;
import tech.wenisch.petri.security.AppUserRepository;

/** The agent runs of one project; the workspace shows it as its Runs tab. */
@Controller
public class ProjectRunsController {
  private final AgentRunService runs;
  private final ProjectAccessService access;
  private final AppUserRepository users;

  public ProjectRunsController(
      AgentRunService runs, ProjectAccessService access, AppUserRepository users) {
    this.runs = runs;
    this.access = access;
    this.users = users;
  }

  @GetMapping("/projects/{projectId}/runs")
  String projectRuns(
      @PathVariable UUID projectId,
      @RequestParam(defaultValue = "false") boolean embedded,
      Principal principal,
      Model model) {
    Project project = access.requireProject(projectId, principal.getName());
    UUID actorId = users.findByEmailIgnoreCase(principal.getName())
        .orElseThrow(() -> new IllegalArgumentException("Unknown user")).getId();
    model.addAttribute("project", project);
    // Chats and workflow stages in one list; the page filters by type.
    model.addAttribute("runs", runs.listForProject(projectId, actorId, true));
    return embedded ? "project-runs :: content" : "project-runs";
  }
}
