package tech.wenisch.petri.agent.change;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "change_proposal")
class ChangeProposal {
  @Id private UUID id;

  @Column(name = "worktree_id", nullable = false)
  private UUID worktreeId;

  @Column(name = "file_path", nullable = false, length = 2048)
  private String filePath;

  @Column(name = "expected_sha256", nullable = false, length = 64)
  private String expectedSha256;

  @Column(name = "replacement_sha256", nullable = false, length = 64)
  private String replacementSha256;

  @Column(name = "replacement_content", nullable = false, columnDefinition = "TEXT")
  private String replacementContent;

  @Column(name = "unified_diff", nullable = false, columnDefinition = "TEXT")
  private String unifiedDiff;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private ChangeProposalStatus status;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "approved_by")
  private UUID approvedBy;

  @Column(name = "approved_at")
  private Instant approvedAt;

  @Column(name = "applied_at")
  private Instant appliedAt;

  @Column(name = "rejected_by")
  private UUID rejectedBy;

  @Column(name = "rejected_at")
  private Instant rejectedAt;

  protected ChangeProposal() {}

  ChangeProposal(
      UUID id,
      UUID worktreeId,
      String filePath,
      String expectedSha256,
      String replacementSha256,
      String replacementContent,
      String unifiedDiff,
      Instant createdAt) {
    this.id = id;
    this.worktreeId = worktreeId;
    this.filePath = filePath;
    this.expectedSha256 = expectedSha256;
    this.replacementSha256 = replacementSha256;
    this.replacementContent = replacementContent;
    this.unifiedDiff = unifiedDiff;
    this.status = ChangeProposalStatus.PROPOSED;
    this.createdAt = createdAt;
  }

  void approve(UUID actorId, Instant now) {
    requireStatus(ChangeProposalStatus.PROPOSED);
    status = ChangeProposalStatus.APPROVED;
    approvedBy = actorId;
    approvedAt = now;
  }

  void reject(UUID actorId, Instant now) {
    requireStatus(ChangeProposalStatus.PROPOSED);
    status = ChangeProposalStatus.REJECTED;
    rejectedBy = actorId;
    rejectedAt = now;
  }

  void applied(Instant now) {
    requireStatus(ChangeProposalStatus.APPROVED);
    status = ChangeProposalStatus.APPLIED;
    appliedAt = now;
  }

  void conflict() {
    requireStatus(ChangeProposalStatus.APPROVED);
    status = ChangeProposalStatus.CONFLICT;
  }

  private void requireStatus(ChangeProposalStatus expected) {
    if (status != expected) {
      throw new IllegalStateException("Change proposal must be " + expected + " but is " + status);
    }
  }

  UUID id() { return id; }
  UUID worktreeId() { return worktreeId; }
  String filePath() { return filePath; }
  String expectedSha256() { return expectedSha256; }
  String replacementSha256() { return replacementSha256; }
  String replacementContent() { return replacementContent; }
  String unifiedDiff() { return unifiedDiff; }
  ChangeProposalStatus status() { return status; }
  Instant createdAt() { return createdAt; }
  UUID approvedBy() { return approvedBy; }
  Instant approvedAt() { return approvedAt; }
  Instant appliedAt() { return appliedAt; }
}
