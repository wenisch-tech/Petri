package tech.wenisch.petri.dashboard;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.project.Project;
import tech.wenisch.petri.project.ProjectRepository;
import tech.wenisch.petri.security.AppUserRepository;

/**
 * Live picture of the user's projects: what is running, what waits for a person, and what changes
 * are pending, per worktree. Read-only aggregation straight from the tables, so it stays cheap
 * enough to poll.
 */
@Service
public class DashboardService {
  private static final int MAX_PROJECTS = 24;
  private static final int MAX_TEXT = 140;

  private final NamedParameterJdbcTemplate jdbc;
  private final ProjectRepository projects;
  private final AppUserRepository users;
  private final Clock clock;

  public DashboardService(
      NamedParameterJdbcTemplate jdbc, ProjectRepository projects, AppUserRepository users) {
    this.jdbc = jdbc;
    this.projects = projects;
    this.users = users;
    this.clock = Clock.systemUTC();
  }

  public enum State { ATTENTION, WAITING, WORKING, IDLE }

  /** The latest result of a branch, whatever it was: COMPLETED, FAILED, CANCELLED, ... */
  public record LastResult(String status, Instant at) {}

  public record LastMessage(String text, Instant at) {}

  public record Worktree(
      UUID id, String branch, State state, String headline, String detail, int pendingChanges,
      int runsTotal, int runsThisWeek, LastResult lastResult, LastMessage lastMessage,
      Instant lastActivityAt, UUID chatId) {}

  public record AutomationInfo(String name, String trigger, String lastStatus, Instant lastAt) {}

  public record ProjectCard(
      UUID id, String name, String repositoryUri, String defaultBranch, State state,
      String headline, Instant lastActivityAt, int activeWorktrees, int working, int waiting,
      int pendingChanges, int runsTotal, int runsThisWeek, List<AutomationInfo> automations,
      List<Worktree> worktrees) {}

  public record Totals(int projects, int working, int waiting, int attention, int pendingChanges,
      int runsToday) {}

  public record Overview(Totals totals, List<ProjectCard> projects, Instant generatedAt) {}

  @Transactional(readOnly = true)
  public Overview overview(String actorEmail) {
    UUID userId = users.findByEmailIgnoreCase(actorEmail)
        .orElseThrow(() -> new IllegalArgumentException("Unknown user")).getId();
    List<Project> owned = projects.findByCreatedByOrderByCreatedAtDesc(userId);
    if (owned.size() > MAX_PROJECTS) owned = owned.subList(0, MAX_PROJECTS);
    Instant now = Instant.now(clock);
    if (owned.isEmpty()) {
      return new Overview(new Totals(0, 0, 0, 0, 0, 0), List.of(), now);
    }
    List<UUID> projectIds = owned.stream().map(Project::getId).toList();

    // Worktrees by project.
    Map<UUID, List<WorktreeRow>> byProject = new LinkedHashMap<>();
    List<UUID> worktreeIds = new ArrayList<>();
    jdbc.query(
        "select id, project_id, branch_name from project_worktree "
            + "where project_id in (:ids) and status = 'ACTIVE' order by created_at",
        new MapSqlParameterSource("ids", projectIds), rs -> {
          WorktreeRow row = new WorktreeRow(
              rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
              rs.getString("branch_name"));
          byProject.computeIfAbsent(row.projectId, key -> new ArrayList<>()).add(row);
          worktreeIds.add(row.id);
        });

    Map<UUID, List<AutomationInfo>> automations = loadAutomations(projectIds);
    Map<UUID, WorktreeFacts> facts = new HashMap<>();
    for (UUID id : worktreeIds) facts.put(id, new WorktreeFacts());
    if (!worktreeIds.isEmpty()) loadFacts(worktreeIds, now, facts);

    List<ProjectCard> cards = new ArrayList<>();
    int working = 0;
    int waiting = 0;
    int attention = 0;
    int pending = 0;
    int runsToday = 0;
    for (Project project : owned) {
      List<Worktree> rows = new ArrayList<>();
      for (WorktreeRow row : byProject.getOrDefault(project.getId(), List.of())) {
        rows.add(describe(row, facts.get(row.id)));
        runsToday += facts.get(row.id).runsToday;
      }
      rows.sort(Comparator
          .comparing((Worktree value) -> value.state().ordinal())
          .thenComparing(value -> value.lastActivityAt() == null ? Instant.EPOCH : value.lastActivityAt(),
              Comparator.reverseOrder()));
      State state = rows.stream().map(Worktree::state).min(Comparator.naturalOrder()).orElse(State.IDLE);
      Worktree lead = rows.stream().filter(value -> value.state() == state).findFirst().orElse(null);
      int projectWorking = (int) rows.stream().filter(value -> value.state() == State.WORKING).count();
      int projectWaiting = (int) rows.stream().filter(value -> value.state() == State.WAITING).count();
      int projectPending = rows.stream().mapToInt(Worktree::pendingChanges).sum();
      int projectRuns = rows.stream().mapToInt(Worktree::runsThisWeek).sum();
      int projectRunsTotal = rows.stream().mapToInt(Worktree::runsTotal).sum();
      Instant last = rows.stream().map(Worktree::lastActivityAt).filter(java.util.Objects::nonNull)
          .max(Comparator.naturalOrder()).orElse(null);
      String headline = rows.isEmpty() ? "No active worktree"
          : lead == null ? "Idle" : lead.branch() + " · " + lead.headline();
      working += projectWorking;
      waiting += projectWaiting;
      attention += (int) rows.stream().filter(value -> value.state() == State.ATTENTION).count();
      pending += projectPending;
      cards.add(new ProjectCard(
          project.getId(), project.getName(), project.getRepositoryUri(), project.getDefaultBranch(),
          state, headline, last, rows.size(), projectWorking, projectWaiting, projectPending,
          projectRunsTotal, projectRuns, automations.getOrDefault(project.getId(), List.of()), rows));
    }
    cards.sort(Comparator
        .comparing((ProjectCard value) -> value.state().ordinal())
        .thenComparing(value -> value.lastActivityAt() == null ? Instant.EPOCH : value.lastActivityAt(),
            Comparator.reverseOrder()));
    return new Overview(
        new Totals(cards.size(), working, waiting, attention, pending, runsToday), cards, now);
  }

  private void loadFacts(List<UUID> worktreeIds, Instant now, Map<UUID, WorktreeFacts> facts) {
    MapSqlParameterSource params = new MapSqlParameterSource("ids", worktreeIds)
        .addValue("week", Timestamp.from(now.minus(Duration.ofDays(7))))
        .addValue("day", Timestamp.from(now.minus(Duration.ofDays(1))))
        .addValue("today", Timestamp.from(now.minus(Duration.ofHours(24))));

    jdbc.query(
        "select worktree_id, status, workflow_role, workflow_phase, prompt, chat_id, created_at, started_at "
            + "from agent_run where worktree_id in (:ids) "
            + "and status in ('PENDING','RUNNING','CANCELLATION_REQUESTED') order by created_at desc",
        params, rs -> {
          WorktreeFacts fact = facts.get(rs.getObject("worktree_id", UUID.class));
          if (fact == null || fact.run != null) return;
          fact.run = new RunFact(rs.getString("status"), rs.getString("workflow_role"),
              rs.getString("workflow_phase"), rs.getString("prompt"),
              rs.getObject("chat_id", UUID.class), instant(rs.getTimestamp("created_at")));
        });
    jdbc.query(
        "select execution_worktree_id, chat_id, status, current_stage, prompt, hold_reason, updated_at "
            + "from agent_workflow where execution_worktree_id in (:ids) "
            + "and status in ('PENDING','RUNNING','WAITING_FOR_USER','HUMAN_HOLD','CANCELLING') "
            + "order by updated_at desc",
        params, rs -> {
          WorktreeFacts fact = facts.get(rs.getObject("execution_worktree_id", UUID.class));
          if (fact == null || fact.workflow != null) return;
          fact.workflow = new WorkflowFact(rs.getString("status"), rs.getString("current_stage"),
              rs.getString("prompt"), rs.getString("hold_reason"),
              rs.getObject("chat_id", UUID.class), instant(rs.getTimestamp("updated_at")));
        });
    jdbc.query(
        "select worktree_id, count(*) as total from change_proposal where worktree_id in (:ids) "
            + "and status in ('PROPOSED','APPROVED') group by worktree_id",
        params, rs -> {
          WorktreeFacts fact = facts.get(rs.getObject("worktree_id", UUID.class));
          if (fact != null) fact.pendingChanges = rs.getInt("total");
        });
    jdbc.query(
        "select worktree_id, count(*) as total, max(created_at) as last_at from agent_run "
            + "where worktree_id in (:ids) and created_at > :week group by worktree_id",
        params, rs -> {
          WorktreeFacts fact = facts.get(rs.getObject("worktree_id", UUID.class));
          if (fact == null) return;
          fact.runsThisWeek = rs.getInt("total");
          fact.lastRunAt = instant(rs.getTimestamp("last_at"));
        });
    jdbc.query(
        "select worktree_id, count(*) as total from agent_run "
            + "where worktree_id in (:ids) and created_at > :today group by worktree_id",
        params, rs -> {
          WorktreeFacts fact = facts.get(rs.getObject("worktree_id", UUID.class));
          if (fact != null) fact.runsToday = rs.getInt("total");
        });
    jdbc.query(
        "select worktree_id, count(*) as total from agent_run where worktree_id in (:ids) group by worktree_id",
        params, rs -> {
          WorktreeFacts fact = facts.get(rs.getObject("worktree_id", UUID.class));
          if (fact != null) fact.runsTotal = rs.getInt("total");
        });
    jdbc.query(
        "select worktree_id, status, created_at, completed_at from ("
            + "select worktree_id, status, created_at, completed_at, row_number() over "
            + "(partition by worktree_id order by created_at desc) as rn "
            + "from agent_run where worktree_id in (:ids)) latest where rn = 1",
        params, rs -> {
          WorktreeFacts fact = facts.get(rs.getObject("worktree_id", UUID.class));
          if (fact == null) return;
          Instant at = instant(rs.getTimestamp("completed_at"));
          fact.lastResult = new LastResult(
              rs.getString("status"), at != null ? at : instant(rs.getTimestamp("created_at")));
        });
    jdbc.query(
        "select worktree_id, text, created_at from ("
            + "select r.worktree_id, substring(t.content, 1, 600) as text, t.created_at, row_number() over "
            + "(partition by r.worktree_id order by t.created_at desc) as rn "
            + "from agent_turn t join agent_run r on r.id = t.run_id "
            + "where r.worktree_id in (:ids) and t.turn_kind = 'MODEL_RESPONSE' "
            + "and t.content not like '{%' and t.content not like '[%') latest where rn = 1",
        params, rs -> {
          WorktreeFacts fact = facts.get(rs.getObject("worktree_id", UUID.class));
          String text = plain(rs.getString("text"));
          if (fact != null && text != null) {
            fact.message = new LastMessage(text, instant(rs.getTimestamp("created_at")));
          }
        });
  }

  private Map<UUID, List<AutomationInfo>> loadAutomations(List<UUID> projectIds) {
    MapSqlParameterSource params = new MapSqlParameterSource("ids", projectIds);
    Map<String, LastResult> latest = new HashMap<>();
    jdbc.query(
        "select project_id, kind, trigger_type, status, created_at from ("
            + "select project_id, kind, trigger_type, status, created_at, row_number() over "
            + "(partition by project_id, kind, trigger_type order by created_at desc) as rn "
            + "from automation_run where project_id in (:ids)) latest where rn = 1",
        params, rs -> {
          latest.put(rs.getObject("project_id", UUID.class) + "|" + rs.getString("kind") + "|"
              + rs.getString("trigger_type"),
              new LastResult(rs.getString("status"), instant(rs.getTimestamp("created_at"))));
        });
    Map<UUID, List<AutomationInfo>> result = new HashMap<>();
    jdbc.query(
        "select project_id, kind, trigger_type from project_automation "
            + "where project_id in (:ids) and enabled = true order by kind, trigger_type",
        params, rs -> {
          UUID projectId = rs.getObject("project_id", UUID.class);
          String kind = rs.getString("kind");
          String trigger = rs.getString("trigger_type");
          LastResult last = latest.get(projectId + "|" + kind + "|" + trigger);
          result.computeIfAbsent(projectId, key -> new ArrayList<>()).add(new AutomationInfo(
              automationName(kind), triggerName(trigger),
              last == null ? null : last.status(), last == null ? null : last.at()));
        });
    return result;
  }

  private static String automationName(String kind) {
    try {
      return tech.wenisch.petri.automation.AutomationKind.valueOf(kind).getTitle();
    } catch (IllegalArgumentException exception) {
      String text = kind.replace('_', ' ').toLowerCase();
      return text.substring(0, 1).toUpperCase() + text.substring(1);
    }
  }

  private static String triggerName(String trigger) {
    return switch (trigger) {
      case "PULL_REQUEST" -> "on pull requests";
      case "CRON" -> "scheduled";
      default -> trigger.toLowerCase().replace('_', ' ');
    };
  }

  /** Chat text reduced to one calm line: no markdown markers, no line breaks. */
  private static String plain(String value) {
    if (value == null) return null;
    String text = value.replaceAll("```[\\s\\S]*?```", " ")
        .replaceAll("[*_`#>]+", "").replaceAll("\\s+", " ").strip();
    if (text.isEmpty()) return null;
    return text.length() > 220 ? text.substring(0, 219) + "…" : text;
  }

  private static Worktree describe(WorktreeRow row, WorktreeFacts fact) {
    State state = State.IDLE;
    String headline = "Idle";
    String detail = null;
    UUID chatId = null;
    Instant last = fact.lastRunAt;
    WorkflowFact workflow = fact.workflow;
    RunFact run = fact.run;
    if (workflow != null) {
      chatId = workflow.chatId;
      last = latest(last, workflow.updatedAt);
      detail = shorten(workflow.prompt);
      switch (workflow.status) {
        case "WAITING_FOR_USER" -> {
          state = State.WAITING;
          headline = switch (workflow.stage) {
            case "WAITING_FOR_PLAN_APPROVAL" -> "Plan waits for your approval";
            case "WAITING_FOR_CLARIFICATION" -> "Agent has questions for you";
            default -> "Waiting for you";
          };
        }
        case "HUMAN_HOLD" -> {
          // A person has to decide: that is waiting for you, not a failure.
          state = State.WAITING;
          headline = "On hold, needs a decision";
          if (workflow.holdReason != null && !workflow.holdReason.isBlank()) detail = shorten(workflow.holdReason);
        }
        case "CANCELLING" -> { state = State.WORKING; headline = "Cancelling"; }
        default -> { state = State.WORKING; headline = stageLabel(workflow.stage); }
      }
    } else if (run != null) {
      state = State.WORKING;
      chatId = run.chatId;
      last = latest(last, run.createdAt);
      detail = shorten(run.prompt);
      headline = run.role != null ? roleLabel(run.role) + " is working" : "Agent is working";
    }
    LastResult result = fact.lastResult;
    if (state == State.IDLE && fact.pendingChanges > 0) {
      state = State.WAITING;
      headline = fact.pendingChanges == 1 ? "1 change to review" : fact.pendingChanges + " changes to review";
    } else if (state == State.IDLE && result != null) {
      headline = switch (result.status()) {
        case "COMPLETED" -> "Last run completed";
        case "FAILED" -> "Last run failed";
        case "BUDGET_EXHAUSTED" -> "Last run hit its budget";
        case "CANCELLED" -> "Last run cancelled";
        default -> "Idle";
      };
      boolean failed = "FAILED".equals(result.status()) || "BUDGET_EXHAUSTED".equals(result.status());
      if (failed && result.at() != null
          && result.at().isAfter(Instant.now().minus(Duration.ofHours(24)))) {
        state = State.ATTENTION;
      }
    }
    if (result != null) last = latest(last, result.at());
    return new Worktree(row.id, row.branch, state, headline, detail, fact.pendingChanges,
        fact.runsTotal, fact.runsThisWeek, result, fact.message, last, chatId);
  }

  private static String stageLabel(String stage) {
    return switch (stage == null ? "" : stage) {
      case "QUEUED" -> "Queued";
      case "ORCHESTRATOR_INTAKE" -> "Orchestrator is reading the task";
      case "PLANNING", "ORCHESTRATOR_PLAN_REVIEW" -> "Planning";
      case "IMPLEMENTING" -> "Implementing";
      case "ORCHESTRATOR_IMPLEMENTATION_REVIEW", "REVIEWING" -> "Reviewing";
      case "ORCHESTRATOR_FINAL_REVIEW" -> "Final review";
      default -> "Working";
    };
  }

  private static String roleLabel(String role) {
    return role.charAt(0) + role.substring(1).toLowerCase();
  }

  private static String shorten(String value) {
    if (value == null) return null;
    String text = value.strip().replaceAll("\\s+", " ");
    return text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT - 1) + "…" : text;
  }

  private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

  private static Instant latest(Instant first, Instant second) {
    if (first == null) return second;
    if (second == null) return first;
    return first.isAfter(second) ? first : second;
  }

  private record WorktreeRow(UUID id, UUID projectId, String branch) {}

  private record RunFact(
      String status, String role, String phase, String prompt, UUID chatId, Instant createdAt) {}

  private record WorkflowFact(
      String status, String stage, String prompt, String holdReason, UUID chatId, Instant updatedAt) {}

  private static final class WorktreeFacts {
    RunFact run;
    WorkflowFact workflow;
    int pendingChanges;
    int runsThisWeek;
    int runsToday;
    int runsTotal;
    LastResult lastResult;
    LastMessage message;
    Instant lastRunAt;
  }
}
