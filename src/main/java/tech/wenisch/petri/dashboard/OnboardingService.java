package tech.wenisch.petri.dashboard;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.project.ProjectRepository;

/**
 * The getting-started checklist on the dashboard: which first steps the user has taken, worked out
 * from what already exists, so there is nothing to tick off by hand.
 */
@Service
public class OnboardingService {
  private final NamedParameterJdbcTemplate jdbc;
  private final ProjectRepository projects;

  public OnboardingService(NamedParameterJdbcTemplate jdbc, ProjectRepository projects) {
    this.jdbc = jdbc;
    this.projects = projects;
  }

  public record Step(String title, String description, boolean done, String href, String action) {}

  public record Checklist(List<Step> steps, int done) {
    public int total() { return steps.size(); }
    public boolean complete() { return done == steps.size(); }
    public int percent() { return steps.isEmpty() ? 0 : done * 100 / steps.size(); }
  }

  @Transactional(readOnly = true)
  public Checklist checklist(UUID userId, String email) {
    var owned = projects.findByCreatedByOrderByCreatedAtDesc(userId);
    List<UUID> projectIds = owned.stream().map(project -> project.getId()).toList();
    // Steps that happen inside a project link to the newest one, or to creating one.
    String project = owned.isEmpty() ? null : "/projects/" + owned.get(0).getId();
    String workspace = owned.isEmpty() ? "/projects/new" : "/workspace" + project;

    boolean git = exists("select 1 from git_account_connection where owner_id = :user "
        + "and status = 'ACTIVE' limit 1", new MapSqlParameterSource("user", userId));
    boolean llm = exists("select 1 from model_provider_instance where enabled = true "
        + "and (owner_id is null or owner_id = :user) limit 1",
        new MapSqlParameterSource("user", userId));
    boolean workflow = exists("select 1 from agent_workflow where created_by = :user limit 1",
        new MapSqlParameterSource("user", userId));
    boolean pullRequest = exists("select 1 from audit_event where actor_email = :email "
        + "and event_action = 'GIT_PULL_REQUEST_OPENED' limit 1",
        new MapSqlParameterSource("email", email));
    var inProjects = new MapSqlParameterSource("ids", projectIds.isEmpty() ? List.of(new UUID(0, 0)) : projectIds);
    boolean scan = exists("select 1 from vulnerability_report where project_id in (:ids) limit 1",
        inProjects);
    boolean review = exists("select 1 from automation_run where project_id in (:ids) "
        + "and kind like 'CODE_REVIEW%' and status in ('REVIEWED', 'POSTED', 'IMPROVING') limit 1",
        inProjects);
    boolean automation = exists("select 1 from project_automation where project_id in (:ids) limit 1",
        inProjects);

    List<Step> steps = List.of(
        new Step("Connect a Git account", "So Petri can import your repositories and open pull requests.",
            git, "/git-accounts", "Connect"),
        new Step("Set up an LLM provider", "The model your agents think with: a key, or your own endpoint.",
            llm, "/model-providers", "Configure"),
        new Step("Add a project", "Import a repository and give agents a place to work.",
            !owned.isEmpty(), "/projects/new", "Add project"),
        new Step("Run a workflow", "Describe a task and let a plan, build and review workflow carry it out.",
            workflow, workspace, "Open workspace"),
        new Step("Open a pull request", "Turn the finished work into a pull request from the workspace.",
            pullRequest, workspace, "Open workspace"),
        new Step("Scan for vulnerabilities", "Find vulnerable dependencies, secrets and misconfigurations.",
            scan, project == null ? "/projects/new" : project + "/vulnerabilities", "Scan"),
        new Step("Get a code review", "Have an agent review a pull request and summarise what to fix.",
            review, project == null ? "/projects/new" : project + "/automations", "Set up review"),
        new Step("Automate it", "Run reviews and scans on every pull request or on a schedule.",
            automation, project == null ? "/projects/new" : project + "/automations", "Add automation"));
    return new Checklist(steps, (int) steps.stream().filter(Step::done).count());
  }

  private boolean exists(String sql, MapSqlParameterSource parameters) {
    return !jdbc.queryForList(sql, parameters, Integer.class).isEmpty();
  }
}
