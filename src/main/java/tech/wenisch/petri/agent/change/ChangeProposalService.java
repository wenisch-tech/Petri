package tech.wenisch.petri.agent.change;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.agent.tool.FileConflictException;
import tech.wenisch.petri.agent.tool.ReplacementPreview;
import tech.wenisch.petri.agent.tool.WorkspaceFileService;
import tech.wenisch.petri.audit.AuditLogService;
import tech.wenisch.petri.security.AppUser;
import tech.wenisch.petri.security.AppUserRepository;
import tech.wenisch.petri.workspace.WorktreeLocator;

@Service
public class ChangeProposalService {
  private final ChangeProposalRepository proposals;
  private final WorktreeLocator worktrees;
  private final WorkspaceFileService files;
  private final AppUserRepository users;
  private final AuditLogService auditLog;
  private final Clock clock;

  @Autowired
  public ChangeProposalService(
      ChangeProposalRepository proposals,
      WorktreeLocator worktrees,
      WorkspaceFileService files,
      AppUserRepository users,
      AuditLogService auditLog) {
    this(proposals, worktrees, files, users, auditLog, Clock.systemUTC());
  }

  ChangeProposalService(
      ChangeProposalRepository proposals,
      WorktreeLocator worktrees,
      WorkspaceFileService files,
      AppUserRepository users,
      AuditLogService auditLog,
      Clock clock) {
    this.proposals = proposals;
    this.worktrees = worktrees;
    this.files = files;
    this.users = users;
    this.auditLog = auditLog;
    this.clock = clock;
  }

  @Transactional
  public ChangeProposalView create(
      UUID worktreeId,
      UUID agentRunId,
      String path,
      String expectedSha256,
      String replacementContent) {
    Path worktree = worktrees.requireActiveWorktree(worktreeId);
    ReplacementPreview preview =
        files.previewReplacement(worktree, path, expectedSha256, replacementContent);
    ChangeProposal proposal = proposals.save(new ChangeProposal(
        UUID.randomUUID(),
        worktreeId,
        agentRunId,
        preview.path(),
        ChangeType.REPLACE,
        preview.expectedSha256(),
        preview.replacementSha256(),
        replacementContent,
        preview.diff(),
        Instant.now(clock)));
    return view(proposal);
  }

  @Transactional
  public ChangeProposalView createFile(
      UUID worktreeId,
      UUID agentRunId,
      String path,
      String content) {
    Path worktree = worktrees.requireActiveWorktree(worktreeId);
    ReplacementPreview preview = files.previewCreation(worktree, path, content);
    ChangeProposal proposal = proposals.save(new ChangeProposal(
        UUID.randomUUID(),
        worktreeId,
        agentRunId,
        preview.path(),
        ChangeType.CREATE,
        null,
        preview.replacementSha256(),
        content,
        preview.diff(),
        Instant.now(clock)));
    return view(proposal);
  }

  @Transactional(readOnly = true)
  public java.util.List<ChangeProposalView> listForAgentRun(UUID agentRunId) {
    return proposals.findByAgentRunIdOrderByCreatedAtAsc(agentRunId).stream()
        .map(ChangeProposalService::view)
        .toList();
  }

  public boolean hasOpenForAgentRun(UUID agentRunId) {
    return proposals.findByAgentRunIdOrderByCreatedAtAsc(agentRunId).stream().anyMatch(value ->
        value.status() == ChangeProposalStatus.PROPOSED || value.status() == ChangeProposalStatus.APPROVED);
  }

  @Transactional(readOnly = true)
  public ChangeProposalView get(UUID proposalId, UUID expectedWorktreeId) {
    ChangeProposal proposal = proposal(proposalId);
    requireWorktree(proposal, expectedWorktreeId);
    return view(proposal);
  }

  @Transactional
  public ChangeProposalView approve(UUID proposalId, String actorEmail) {
    ChangeProposal proposal = proposal(proposalId);
    AppUser actor = actor(actorEmail);
    proposal.approve(actor.getId(), Instant.now(clock));
    auditLog.record(
        actor.getEmail(), "CHANGE_PROPOSAL_APPROVED", "CHANGE_PROPOSAL", proposal.id(),
        "Approved change to " + proposal.filePath());
    return view(proposal);
  }

  @Transactional
  public ChangeProposalView reject(UUID proposalId, String actorEmail) {
    ChangeProposal proposal = proposal(proposalId);
    AppUser actor = actor(actorEmail);
    proposal.reject(actor.getId(), Instant.now(clock));
    auditLog.record(
        actor.getEmail(), "CHANGE_PROPOSAL_REJECTED", "CHANGE_PROPOSAL", proposal.id(),
        "Rejected change to " + proposal.filePath());
    return view(proposal);
  }

  @Transactional
  public ChangeProposalView apply(UUID proposalId) {
    ChangeProposal proposal = proposal(proposalId);
    if (proposal.status() != ChangeProposalStatus.APPROVED) {
      throw new IllegalStateException("Change proposal must be approved before application");
    }
    AppUser approver = users.findById(proposal.approvedBy())
        .orElseThrow(() -> new IllegalStateException("Approving user no longer exists"));
    Path worktree = worktrees.requireActiveWorktree(proposal.worktreeId());
    try {
      if (proposal.changeType() == ChangeType.CREATE) {
        files.applyCreation(
            worktree,
            proposal.filePath(),
            proposal.replacementContent());
      } else {
        files.applyReplacement(
            worktree,
            proposal.filePath(),
            proposal.expectedSha256(),
            proposal.replacementContent());
      }
      proposal.applied(Instant.now(clock));
      auditLog.record(
          approver.getEmail(), "CHANGE_PROPOSAL_APPLIED", "CHANGE_PROPOSAL", proposal.id(),
          "Applied approved change to " + proposal.filePath());
    } catch (FileConflictException exception) {
      proposal.conflict();
      auditLog.record(
          approver.getEmail(), "CHANGE_PROPOSAL_CONFLICT", "CHANGE_PROPOSAL", proposal.id(),
          "Refused stale change to " + proposal.filePath());
    }
    return view(proposal);
  }

  private ChangeProposal proposal(UUID proposalId) {
    return proposals.findById(proposalId)
        .orElseThrow(() -> new IllegalArgumentException("Unknown change proposal"));
  }

  private AppUser actor(String actorEmail) {
    return users.findByEmailIgnoreCase(actorEmail)
        .orElseThrow(() -> new IllegalArgumentException("Unknown user"));
  }

  private static void requireWorktree(ChangeProposal proposal, UUID expectedWorktreeId) {
    if (!proposal.worktreeId().equals(expectedWorktreeId)) {
      throw new IllegalArgumentException("Change proposal belongs to another worktree");
    }
  }

  private static ChangeProposalView view(ChangeProposal proposal) {
    return new ChangeProposalView(
        proposal.id(), proposal.worktreeId(), proposal.agentRunId(), proposal.filePath(),
        proposal.changeType(),
        proposal.expectedSha256(),
        proposal.replacementSha256(), proposal.unifiedDiff(), proposal.status(),
        proposal.createdAt(), proposal.approvedAt(), proposal.appliedAt());
  }
}
