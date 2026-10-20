package tech.wenisch.petri.dashboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.dashboard.DashboardService.State;
import tech.wenisch.petri.project.Project;
import tech.wenisch.petri.project.ProjectRepository;
import tech.wenisch.petri.security.AppUser;
import tech.wenisch.petri.security.AppUserRepository;

@SpringBootTest(properties =
    "petri.secrets.master-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=")
@Transactional
class DashboardServiceTest {
  @Autowired DashboardService dashboard;
  @Autowired AppUserRepository users;
  @Autowired ProjectRepository projects;
  @Autowired JdbcTemplate jdbc;

  private AppUser alice;
  private Project project;

  @BeforeEach
  void setUp() {
    // The fixtures only carry the columns the dashboard reads; skip the unrelated foreign keys.
    jdbc.execute("SET REFERENTIAL_INTEGRITY FALSE");
    alice = users.save(new AppUser(UUID.randomUUID(), "alice@petri.local", "unused", "USER"));
    project = projects.save(new Project(UUID.randomUUID(), "Demo", "demo",
        "https://example.test/acme/demo.git", "main", alice.getId(), Instant.now()));
  }

  @Test
  void unknownUsersAreRejected() {
    assertThatThrownBy(() -> dashboard.overview("nobody@petri.local"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aUserWithoutProjectsGetsAnEmptyOverview() {
    users.save(new AppUser(UUID.randomUUID(), "bob@petri.local", "unused", "USER"));

    var overview = dashboard.overview("bob@petri.local");

    assertThat(overview.projects()).isEmpty();
    assertThat(overview.totals().projects()).isZero();
  }

  @Test
  void aProjectWithoutWorktreesSaysSo() {
    var card = dashboard.overview("alice@petri.local").projects().get(0);

    assertThat(card.headline()).isEqualTo("No active worktree");
    assertThat(card.state()).isEqualTo(State.IDLE);
    assertThat(card.worktrees()).isEmpty();
  }

  @Test
  void worktreeStatesAreDerivedFromWorkflowsRunsAndChanges() {
    UUID approval = worktree("plan-approval");
    workflow(approval, "WAITING_FOR_USER", "WAITING_FOR_PLAN_APPROVAL", null);
    UUID clarification = worktree("clarify");
    workflow(clarification, "WAITING_FOR_USER", "WAITING_FOR_CLARIFICATION", null);
    UUID hold = worktree("hold");
    workflow(hold, "HUMAN_HOLD", "IMPLEMENTING", "Needs a human decision");
    UUID implementing = worktree("implementing");
    workflow(implementing, "RUNNING", "IMPLEMENTING", null);
    UUID cancelling = worktree("cancelling");
    workflow(cancelling, "CANCELLING", "IMPLEMENTING", null);
    UUID planner = worktree("planner");
    run(planner, "RUNNING", "PLANNER", Instant.now());
    UUID plain = worktree("plain-run");
    run(plain, "PENDING", null, Instant.now());
    UUID changes = worktree("changes");
    changeProposal(changes);
    changeProposal(changes);
    UUID failed = worktree("failed");
    run(failed, "FAILED", null, Instant.now());
    UUID oldFailure = worktree("old-failure");
    run(oldFailure, "FAILED", null, Instant.now().minusSeconds(3 * 24 * 3600));
    UUID completed = worktree("completed");
    UUID completedRun = run(completed, "COMPLETED", null, Instant.now());
    turn(completedRun, "MODEL_RESPONSE", "All **done**\n```java\nint x;\n```\nthanks");
    UUID idle = worktree("idle");

    var card = dashboard.overview("alice@petri.local").projects().get(0);
    var byBranch = new java.util.HashMap<String, DashboardService.Worktree>();
    card.worktrees().forEach(row -> byBranch.put(row.branch(), row));

    assertThat(byBranch.get("plan-approval").state()).isEqualTo(State.WAITING);
    assertThat(byBranch.get("plan-approval").headline()).isEqualTo("Plan waits for your approval");
    assertThat(byBranch.get("clarify").headline()).isEqualTo("Agent has questions for you");
    assertThat(byBranch.get("hold").state()).isEqualTo(State.WAITING);
    assertThat(byBranch.get("hold").detail()).isEqualTo("Needs a human decision");
    assertThat(byBranch.get("implementing").state()).isEqualTo(State.WORKING);
    assertThat(byBranch.get("implementing").headline()).isEqualTo("Implementing");
    assertThat(byBranch.get("cancelling").headline()).isEqualTo("Cancelling");
    assertThat(byBranch.get("planner").headline()).isEqualTo("Planner is working");
    assertThat(byBranch.get("plain-run").headline()).isEqualTo("Agent is working");
    assertThat(byBranch.get("changes").state()).isEqualTo(State.WAITING);
    assertThat(byBranch.get("changes").headline()).isEqualTo("2 changes to review");
    assertThat(byBranch.get("changes").pendingChanges()).isEqualTo(2);
    assertThat(byBranch.get("failed").state()).isEqualTo(State.ATTENTION);
    assertThat(byBranch.get("failed").headline()).isEqualTo("Last run failed");
    assertThat(byBranch.get("old-failure").state()).isEqualTo(State.IDLE);
    assertThat(byBranch.get("completed").headline()).isEqualTo("Last run completed");
    assertThat(byBranch.get("completed").lastMessage().text()).isEqualTo("All done thanks");
    assertThat(byBranch.get("completed").runsTotal()).isEqualTo(1);
    assertThat(byBranch.get("idle").headline()).isEqualTo("Idle");

    assertThat(card.state()).isEqualTo(State.ATTENTION);
    assertThat(card.activeWorktrees()).isEqualTo(12);
    assertThat(card.working()).isEqualTo(4);
    assertThat(card.waiting()).isEqualTo(4);
    assertThat(card.pendingChanges()).isEqualTo(2);
    assertThat(card.headline()).startsWith("failed · ");
    var totals = dashboard.overview("alice@petri.local").totals();
    assertThat(totals.attention()).isEqualTo(1);
    assertThat(totals.runsToday()).isEqualTo(4);
  }

  @Test
  void otherLastResultsHaveTheirOwnHeadlines() {
    run(worktree("budget"), "BUDGET_EXHAUSTED", null, Instant.now());
    run(worktree("cancelled"), "CANCELLED", null, Instant.now());
    run(worktree("odd"), "SOMETHING_ELSE", null, Instant.now());

    var rows = dashboard.overview("alice@petri.local").projects().get(0).worktrees();

    assertThat(rows).extracting(DashboardService.Worktree::headline)
        .contains("Last run hit its budget", "Last run cancelled", "Idle");
    assertThat(rows).filteredOn(row -> row.branch().equals("budget"))
        .allMatch(row -> row.state() == State.ATTENTION);
  }

  @Test
  void enabledAutomationsAreListedWithTheirLastRun() {
    automation("CODE_REVIEW", "PULL_REQUEST", true);
    automation("CVE_SCAN", "CRON", true);
    automation("MADE_UP_KIND", "WEBHOOK_PUSH", true);
    automation("VULNERABILITY_SCAN", "PULL_REQUEST", false);
    jdbc.update("insert into automation_run (id, project_id, kind, trigger_type, external_ref, "
        + "head_sha, status, post_mode, created_at, updated_at) values (?,?,?,?,?,?,?,?,?,?)",
        UUID.randomUUID(), project.getId(), "CODE_REVIEW", "PULL_REQUEST", "7", "abc", "COMPLETED",
        "AUTO", now(), now());

    var automations = dashboard.overview("alice@petri.local").projects().get(0).automations();

    assertThat(automations).hasSize(3);
    assertThat(automations).extracting(DashboardService.AutomationInfo::trigger)
        .containsExactlyInAnyOrder("on pull requests", "scheduled", "webhook push");
    assertThat(automations).filteredOn(info -> info.lastStatus() != null).hasSize(1)
        .allMatch(info -> info.lastStatus().equals("COMPLETED") && info.lastAt() != null);
    assertThat(automations).extracting(DashboardService.AutomationInfo::name)
        .contains("Made up kind");
  }

  @Test
  void projectsOfOtherUsersAreNotShown() {
    AppUser bob = users.save(new AppUser(UUID.randomUUID(), "bob@petri.local", "unused", "USER"));
    projects.save(new Project(UUID.randomUUID(), "Other", "other",
        "https://example.test/acme/other.git", "main", bob.getId(), Instant.now()));

    var names = dashboard.overview("alice@petri.local").projects().stream()
        .map(DashboardService.ProjectCard::name).toList();

    assertThat(names).containsExactly("Demo");
  }

  private UUID worktree(String branch) {
    UUID id = UUID.randomUUID();
    jdbc.update("insert into project_worktree (id, project_id, git_revision, relative_path, status, "
        + "created_by, created_at, branch_name) values (?,?,?,?,?,?,?,?)",
        id, project.getId(), "abc123", "wt/" + id, "ACTIVE", alice.getId(), now(), branch);
    return id;
  }

  private void workflow(UUID worktree, String status, String stage, String holdReason) {
    jdbc.update("insert into agent_workflow (id, chat_id, chat_message_index, execution_worktree_id, "
        + "prompt, status, current_stage, hold_reason, created_by, created_at, updated_at) "
        + "values (?,?,?,?,?,?,?,?,?,?,?)",
        UUID.randomUUID(), UUID.randomUUID(), 0, worktree, "Add a feature", status, stage,
        holdReason, alice.getId(), now(), now());
  }

  private UUID run(UUID worktree, String status, String role, Instant at) {
    UUID id = UUID.randomUUID();
    boolean finished = !status.equals("RUNNING") && !status.equals("PENDING");
    jdbc.update("insert into agent_run (id, worktree_id, provider, model_name, prompt, status, "
        + "max_model_turns, model_turn_count, next_turn_index, created_by, created_at, completed_at, "
        + "chat_id, workflow_role) values (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
        id, worktree, "OPENAI", "gpt", "Do the thing", status, 10, 0, 0, alice.getId(),
        at(at), finished ? at(at) : null, UUID.randomUUID(), role);
    return id;
  }

  private void turn(UUID run, String kind, String content) {
    jdbc.update("insert into agent_turn (id, run_id, turn_index, turn_kind, content, created_at) "
        + "values (?,?,?,?,?,?)", UUID.randomUUID(), run, 0, kind, content, now());
  }

  private void changeProposal(UUID worktree) {
    jdbc.update("insert into change_proposal (id, worktree_id, file_path, replacement_sha256, "
        + "replacement_content, unified_diff, status, created_at) values (?,?,?,?,?,?,?,?)",
        UUID.randomUUID(), worktree, "A.java", "x", "class A {}", "diff", "PROPOSED", now());
  }

  private void automation(String kind, String trigger, boolean enabled) {
    jdbc.update("insert into project_automation (id, project_id, kind, enabled, trigger_type, "
        + "created_at, updated_at) values (?,?,?,?,?,?,?)",
        UUID.randomUUID(), project.getId(), kind, enabled, trigger, now(), now());
  }

  private static OffsetDateTime now() { return at(Instant.now()); }

  private static OffsetDateTime at(Instant instant) { return instant.atOffset(ZoneOffset.UTC); }
}
