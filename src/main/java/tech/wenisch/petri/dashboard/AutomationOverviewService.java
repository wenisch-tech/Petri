package tech.wenisch.petri.dashboard;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.automation.AutomationKind;
import tech.wenisch.petri.project.Project;
import tech.wenisch.petri.project.ProjectRepository;
import tech.wenisch.petri.security.AppUserRepository;

/** The latest automation runs and the configured automations across all of a user's projects. */
@Service
public class AutomationOverviewService {
  public static final int PAGE_SIZE = 20;

  private final NamedParameterJdbcTemplate jdbc;
  private final ProjectRepository projects;
  private final AppUserRepository users;

  public AutomationOverviewService(
      NamedParameterJdbcTemplate jdbc, ProjectRepository projects, AppUserRepository users) {
    this.jdbc = jdbc;
    this.projects = projects;
    this.users = users;
  }

  public record Run(
      UUID id, UUID projectId, String projectName, String name, String trigger, String reference,
      String title, String webUrl, String status, UUID agentRunId, String error, String commentUrl,
      Instant createdAt, String headSha, int changedFiles, Long durationSeconds, String verdict,
      String summary) {}

  public record ProjectOption(UUID id, String name) {}

  public record Configured(String name, String trigger, boolean enabled) {}

  public record ProjectAutomations(
      UUID projectId, String projectName, List<Configured> automations, Run latest, int runs) {}

  public record Counts(int active, int awaitingApproval, int failedToday, int postedWeek) {}

  public record Overview(
      Counts counts, List<ProjectAutomations> projects, List<Run> runs, String filter, String query,
      UUID projectId, List<ProjectOption> projectOptions, int page, int pageSize, int total) {

    public int pages() { return Math.max(1, (total + pageSize - 1) / pageSize); }
    public int from() { return total == 0 ? 0 : page * pageSize + 1; }
    public int to() { return Math.min(total, (page + 1) * pageSize); }
  }

  @Transactional(readOnly = true)
  public Overview overview(
      String actorEmail, String filter, String query, UUID projectFilter, int page) {
    UUID userId = users.findByEmailIgnoreCase(actorEmail)
        .orElseThrow(() -> new IllegalArgumentException("Unknown user")).getId();
    List<Project> owned = projects.findByCreatedByOrderByCreatedAtDesc(userId);
    String normalized = normalize(filter);
    if (owned.isEmpty()) {
      return new Overview(new Counts(0, 0, 0, 0), List.of(), List.of(), normalized, "", null,
          List.of(), 0, PAGE_SIZE, 0);
    }
    List<UUID> ids = owned.stream().map(Project::getId).toList();
    Map<UUID, String> names = new LinkedHashMap<>();
    owned.forEach(project -> names.put(project.getId(), project.getName()));
    Instant now = Instant.now();
    MapSqlParameterSource params = new MapSqlParameterSource("ids", ids)
        .addValue("day", Timestamp.from(now.minus(Duration.ofDays(1))))
        .addValue("week", Timestamp.from(now.minus(Duration.ofDays(7))));

    int[] counts = new int[4];
    jdbc.query(
        "select "
            + "sum(case when status in ('QUEUED','RUNNING') then 1 else 0 end) as active, "
            + "sum(case when status = 'AWAITING_APPROVAL' then 1 else 0 end) as awaiting, "
            + "sum(case when status = 'FAILED' and created_at > :day then 1 else 0 end) as failed, "
            + "sum(case when status = 'POSTED' and created_at > :week then 1 else 0 end) as posted "
            + "from automation_run where project_id in (:ids)",
        params, rs -> {
          counts[0] = rs.getInt("active");
          counts[1] = rs.getInt("awaiting");
          counts[2] = rs.getInt("failed");
          counts[3] = rs.getInt("posted");
        });

    String text = query == null ? "" : query.strip();
    UUID projectId = projectFilter != null && ids.contains(projectFilter) ? projectFilter : null;
    StringBuilder where = new StringBuilder(" where project_id in (:ids)");
    switch (normalized) {
      case "active" -> where.append(" and status in ('QUEUED','RUNNING')");
      case "approval" -> where.append(" and status = 'AWAITING_APPROVAL'");
      case "failed" -> where.append(" and status = 'FAILED'");
      default -> { }
    }
    if (projectId != null) {
      where.append(" and project_id = :project");
      params.addValue("project", projectId);
    }
    if (!text.isEmpty()) {
      where.append(" and (lower(title) like :q or lower(external_ref) like :q"
          + " or lower(head_sha) like :q or lower(coalesce(error, '')) like :q)");
      params.addValue("q", "%" + text.toLowerCase().replace("%", "").replace("_", "") + "%");
    }
    Integer total = jdbc.queryForObject(
        "select count(*) from automation_run" + where, params, Integer.class);
    int totalRuns = total == null ? 0 : total;
    int lastPage = Math.max(0, (totalRuns - 1) / PAGE_SIZE);
    int current = Math.max(0, Math.min(page, lastPage));
    params.addValue("limit", PAGE_SIZE).addValue("offset", current * PAGE_SIZE);
    List<Run> runs = new ArrayList<>();
    jdbc.query(
        "select " + RUN_COLUMNS + " from automation_run" + where
            + " order by created_at desc limit :limit offset :offset",
        params, rs -> { runs.add(run(rs, names)); });

    Map<UUID, List<Configured>> configured = new LinkedHashMap<>();
    jdbc.query(
        "select project_id, kind, trigger_type, enabled from project_automation "
            + "where project_id in (:ids) order by kind, trigger_type",
        params, rs -> {
          configured.computeIfAbsent(rs.getObject("project_id", UUID.class), key -> new ArrayList<>())
              .add(new Configured(kindName(rs.getString("kind")),
                  triggerName(rs.getString("trigger_type")), rs.getBoolean("enabled")));
        });
    Map<UUID, Run> latest = new LinkedHashMap<>();
    Map<UUID, Integer> totals = new LinkedHashMap<>();
    jdbc.query(
        "select project_id, count(*) as total from automation_run "
            + "where project_id in (:ids) group by project_id",
        params, rs -> { totals.put(rs.getObject("project_id", UUID.class), rs.getInt("total")); });
    jdbc.query(
        "select " + RUN_COLUMNS + " from ("
            + "select " + RUN_COLUMNS + ", row_number() over "
            + "(partition by project_id order by created_at desc) as rn "
            + "from automation_run where project_id in (:ids)) x where rn = 1",
        params, rs -> { Run value = run(rs, names); latest.put(value.projectId(), value); });

    List<ProjectAutomations> perProject = new ArrayList<>();
    for (Project project : owned) {
      List<Configured> list = configured.getOrDefault(project.getId(), List.of());
      if (list.stream().noneMatch(Configured::enabled) && !latest.containsKey(project.getId())) continue;
      perProject.add(new ProjectAutomations(
          project.getId(), project.getName(), list, latest.get(project.getId()),
          totals.getOrDefault(project.getId(), 0)));
    }
    perProject.sort(Comparator.comparing(
        (ProjectAutomations value) -> value.latest() == null ? Instant.EPOCH : value.latest().createdAt(),
        Comparator.reverseOrder()));
    List<ProjectOption> options = owned.stream()
        .map(project -> new ProjectOption(project.getId(), project.getName())).toList();
    return new Overview(new Counts(counts[0], counts[1], counts[2], counts[3]), perProject, runs,
        normalized, text, projectId, options, current, PAGE_SIZE, totalRuns);
  }

  private static final String RUN_COLUMNS =
      "id, project_id, kind, trigger_type, external_ref, head_sha, title, web_url, status, "
          + "agent_run_id, error, comment_url, created_at, updated_at, "
          + "substring(changed_files, 1, 4000) as changed_files, "
          + "substring(review_markdown, 1, 600) as review_markdown";

  private static Run run(java.sql.ResultSet rs, Map<UUID, String> names) throws java.sql.SQLException {
    UUID projectId = rs.getObject("project_id", UUID.class);
    String status = rs.getString("status");
    Instant created = instant(rs.getTimestamp("created_at"));
    Instant updated = instant(rs.getTimestamp("updated_at"));
    boolean finished = !("QUEUED".equals(status) || "RUNNING".equals(status));
    Long duration = finished && created != null && updated != null
        ? Math.max(0, Duration.between(created, updated).getSeconds()) : null;
    String files = rs.getString("changed_files");
    int fileCount = files == null || files.isBlank() ? 0
        : (int) files.lines().filter(line -> !line.isBlank()).count();
    return new Run(
        rs.getObject("id", UUID.class), projectId, names.get(projectId),
        kindName(rs.getString("kind")), triggerName(rs.getString("trigger_type")),
        rs.getString("external_ref"), rs.getString("title"), rs.getString("web_url"), status,
        rs.getObject("agent_run_id", UUID.class), rs.getString("error"), rs.getString("comment_url"),
        created, rs.getString("head_sha"), fileCount, duration,
        verdict(rs.getString("review_markdown")), summary(rs.getString("review_markdown")));
  }

  /** Hidden HTML comments such as the review's commit marker are never content. */
  private static String withoutMarkers(String markdown) {
    return markdown.replaceAll("(?s)<!--.*?-->", "");
  }

  /** The verdict line of a posted review, for example "Comments" or "Looks good". */
  private static String verdict(String markdown) {
    if (markdown == null) return null;
    java.util.regex.Matcher matcher = java.util.regex.Pattern
        .compile("(?im)^[#*\\s>-]*verdict[:*\\s]+([^\\n*]{1,30})").matcher(withoutMarkers(markdown));
    return matcher.find() ? matcher.group(1).strip() : null;
  }

  /** The first sentence-ish of a review as one plain line. */
  private static String summary(String markdown) {
    if (markdown == null) return null;
    String text = withoutMarkers(markdown).replaceAll("```[\\s\\S]*?```", " ")
        .replaceAll("(?im)^.*verdict.*$", " ").replaceAll("(?im)^[#*\\s]*petri code review[#*\\s]*$", " ")
        .replaceAll("[*_`#>|-]+", " ").replaceAll("\\s+", " ").strip();
    if (text.isEmpty()) return null;
    return text.length() > 180 ? text.substring(0, 179) + "…" : text;
  }

  private static String normalize(String filter) {
    return switch (filter == null ? "" : filter) {
      case "active", "approval", "failed" -> filter;
      default -> "all";
    };
  }

  private static String kindName(String kind) {
    try {
      return AutomationKind.valueOf(kind).getTitle();
    } catch (IllegalArgumentException exception) {
      String text = kind.replace('_', ' ').toLowerCase();
      return text.substring(0, 1).toUpperCase() + text.substring(1);
    }
  }

  private static String triggerName(String trigger) {
    return switch (trigger) {
      case "PULL_REQUEST" -> "pull requests";
      case "CRON" -> "schedule";
      default -> trigger.toLowerCase().replace('_', ' ');
    };
  }

  private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
}
