package tech.wenisch.petri.git;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "project_worktree")
class ProjectWorktree {
  @Id private UUID id;

  @Column(name = "project_id", nullable = false)
  private UUID projectId;

  @Column(name = "git_revision", nullable = false, length = 255)
  private String gitRevision;

  @Column(name = "branch_name", length = 255)
  private String branchName;

  @Column(name = "upstream_branch", length = 255)
  private String upstreamBranch;

  @Column(name = "relative_path", nullable = false, unique = true, length = 255)
  private String relativePath;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private ProjectWorktreeStatus status;

  @Column(name = "created_by", nullable = false)
  private UUID createdBy;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "removed_by")
  private UUID removedBy;

  @Column(name = "removed_at")
  private Instant removedAt;

  protected ProjectWorktree() {}

  ProjectWorktree(
      UUID id,
      UUID projectId,
      String gitRevision,
      String relativePath,
      UUID createdBy,
      Instant createdAt) {
    this.id = id;
    this.projectId = projectId;
    this.gitRevision = gitRevision;
    this.relativePath = relativePath;
    this.status = ProjectWorktreeStatus.ACTIVE;
    this.createdBy = createdBy;
    this.createdAt = createdAt;
  }

  ProjectWorktree(
      UUID id,
      UUID projectId,
      String gitRevision,
      String branchName,
      String upstreamBranch,
      String relativePath,
      UUID createdBy,
      Instant createdAt) {
    this(id, projectId, gitRevision, relativePath, createdBy, createdAt);
    this.branchName = branchName;
    this.upstreamBranch = upstreamBranch;
  }

  void removed(UUID actorId, Instant now) {
    status = ProjectWorktreeStatus.REMOVED;
    removedBy = actorId;
    removedAt = now;
  }

  UUID id() { return id; }
  UUID projectId() { return projectId; }
  String gitRevision() { return gitRevision; }
  String branchName() { return branchName; }
  String upstreamBranch() { return upstreamBranch; }
  boolean detached() { return branchName == null; }
  void upstreamBranch(String value) { upstreamBranch = value; }
  String relativePath() { return relativePath; }
  ProjectWorktreeStatus status() { return status; }
  Instant createdAt() { return createdAt; }
  Instant removedAt() { return removedAt; }
}
