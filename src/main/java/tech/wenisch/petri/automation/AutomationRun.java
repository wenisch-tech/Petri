package tech.wenisch.petri.automation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "automation_run")
public class AutomationRun {
  @Id private UUID id;

  @Column(name = "project_id", nullable = false)
  private UUID projectId;

  @jakarta.persistence.Convert(converter = AutomationKindConverter.class)
  @Column(nullable = false, length = 64)
  private AutomationKind kind;

  @Enumerated(EnumType.STRING)
  @Column(name = "trigger_type", nullable = false, length = 32)
  private AutomationTrigger trigger;

  @Column(name = "external_ref", nullable = false, length = 64)
  private String externalRef;

  @Column(name = "head_sha", nullable = false, length = 64)
  private String headSha;

  @Column(length = 500)
  private String title;

  @Column(name = "web_url", length = 2048)
  private String webUrl;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 24)
  private AutomationRunStatus status;

  @Column(name = "agent_run_id")
  private UUID agentRunId;

  @Column(name = "worktree_id")
  private UUID worktreeId;

  @Column(name = "changed_files", columnDefinition = "TEXT")
  private String changedFiles;

  @Column(name = "review_markdown", columnDefinition = "TEXT")
  private String reviewMarkdown;

  /** The structured review, kept so a later push can be compared with it. */
  @Column(name = "review_json", columnDefinition = "TEXT")
  private String reviewJson;

  @Column(name = "comment_url", length = 2048)
  private String commentUrl;

  @Column(length = 1000)
  private String error;

  /** Plain-language outcome of the part after the review, such as the pushed commit. */
  @Column(length = 1000)
  private String note;

  /** FIX (the workflow works), CHECKS (the pull request waits for its checks). */
  @Column(length = 24)
  private String phase;

  @Column(name = "fix_branch", length = 255)
  private String fixBranch;

  @Column(name = "fix_pr_number")
  private Long fixPrNumber;

  @Column(name = "improve_workflow_id")
  private UUID improveWorkflowId;

  @Column(name = "improve_worktree_id")
  private UUID improveWorktreeId;

  @Enumerated(EnumType.STRING)
  @Column(name = "post_mode", nullable = false, length = 16)
  private AutomationPostMode postMode;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected AutomationRun() {}

  public AutomationRun(
      UUID id, UUID projectId, AutomationKind kind, AutomationTrigger trigger, String externalRef,
      String headSha, String title, String webUrl, AutomationPostMode postMode, Instant now) {
    this.id = id;
    this.projectId = projectId;
    this.kind = kind;
    this.trigger = trigger;
    this.externalRef = externalRef;
    this.headSha = headSha;
    this.title = title == null ? null : truncate(title, 500);
    this.webUrl = webUrl;
    this.postMode = postMode;
    this.status = AutomationRunStatus.QUEUED;
    this.createdAt = now;
    this.updatedAt = now;
  }

  void queued(AutomationPostMode postMode, Instant now) {
    this.status = AutomationRunStatus.QUEUED;
    this.postMode = postMode;
    this.error = null;
    this.reviewMarkdown = null;
    this.commentUrl = null;
    this.note = null;
    this.improveWorkflowId = null;
    this.improveWorktreeId = null;
    this.updatedAt = now;
  }

  void running(Instant now) { transition(AutomationRunStatus.RUNNING, null, now); }

  void workspace(UUID worktreeId, String changedFiles, Instant now) {
    this.worktreeId = worktreeId;
    this.changedFiles = changedFiles;
    this.updatedAt = now;
  }

  void agentRun(UUID agentRunId, Instant now) {
    this.agentRunId = agentRunId;
    this.updatedAt = now;
  }

  void review(String markdown, Instant now) {
    this.reviewMarkdown = markdown;
    this.updatedAt = now;
  }

  void review(String markdown, String json, Instant now) {
    this.reviewJson = json;
    review(markdown, now);
  }

  void awaitingApproval(Instant now) { transition(AutomationRunStatus.AWAITING_APPROVAL, null, now); }

  void posted(String commentUrl, Instant now) {
    this.commentUrl = commentUrl;
    transition(AutomationRunStatus.POSTED, null, now);
  }

  void reviewed(Instant now) { transition(AutomationRunStatus.REVIEWED, null, now); }

  void improving(UUID workflowId, UUID worktreeId, Instant now) {
    this.improveWorkflowId = workflowId;
    this.improveWorktreeId = worktreeId;
    transition(AutomationRunStatus.IMPROVING, null, now);
  }

  void phase(String phase, Instant now) {
    this.phase = phase;
    this.updatedAt = now;
  }

  void fixBranch(String branch, Instant now) {
    this.fixBranch = branch;
    this.updatedAt = now;
  }

  void fixPullRequest(long number, String url, Instant now) {
    this.fixPrNumber = number;
    this.commentUrl = url;
    this.updatedAt = now;
  }

  void note(String note, Instant now) {
    this.note = note == null ? null : truncate(note, 1000);
    this.updatedAt = now;
  }

  void skipped(String reason, Instant now) { transition(AutomationRunStatus.SKIPPED, reason, now); }

  void failed(String reason, Instant now) { transition(AutomationRunStatus.FAILED, reason, now); }

  private void transition(AutomationRunStatus status, String message, Instant now) {
    this.status = status;
    this.error = message == null ? null : truncate(message, 1000);
    this.updatedAt = now;
  }

  private static String truncate(String value, int max) {
    return value.length() <= max ? value : value.substring(0, max - 3) + "...";
  }

  public UUID getId() { return id; }
  public UUID getProjectId() { return projectId; }
  public AutomationKind getKind() { return kind; }
  public AutomationTrigger getTrigger() { return trigger; }
  public String getExternalRef() { return externalRef; }
  public String getHeadSha() { return headSha; }
  public String getTitle() { return title; }
  public String getWebUrl() { return webUrl; }
  public AutomationRunStatus getStatus() { return status; }
  public UUID getAgentRunId() { return agentRunId; }
  public UUID getWorktreeId() { return worktreeId; }
  public String getChangedFiles() { return changedFiles; }
  public String getReviewMarkdown() { return reviewMarkdown; }
  public String getReviewJson() { return reviewJson; }
  public String getCommentUrl() { return commentUrl; }
  public String getError() { return error; }
  public String getNote() { return note; }
  public String getPhase() { return phase; }
  public String getFixBranch() { return fixBranch; }
  public Long getFixPrNumber() { return fixPrNumber; }
  public UUID getImproveWorkflowId() { return improveWorkflowId; }
  public UUID getImproveWorktreeId() { return improveWorktreeId; }
  public AutomationPostMode getPostMode() { return postMode; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
}
