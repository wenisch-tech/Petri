package tech.wenisch.petri.automation;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.git.provider.PullRequestRef;

/** Persistence of automation run state; every method is its own short transaction. */
@Service
public class AutomationRunService {
  static final Duration STALE_AFTER = Duration.ofMinutes(5);

  private final AutomationRunRepository runs;

  public AutomationRunService(AutomationRunRepository runs) {
    this.runs = runs;
  }

  /**
   * Registers a run for a pull request head, or returns the existing one's id when it should run
   * again (stale or forced retry). Empty when this head was already handled.
   */
  @Transactional
  public Optional<UUID> register(
      UUID projectId, AutomationKind kind, AutomationTrigger trigger, PullRequestRef pr,
      AutomationPostMode postMode, boolean retryFailed, java.util.Set<UUID> inFlight) {
    String ref = Long.toString(pr.number());
    Instant now = Instant.now();
    Optional<AutomationRun> existing = runs.findByProjectIdAndKindAndExternalRefAndHeadSha(
        projectId, kind, ref, pr.headSha());
    if (existing.isPresent()) {
      AutomationRun run = existing.get();
      boolean stale = run.getStatus().active() && !inFlight.contains(run.getId())
          && run.getUpdatedAt().isBefore(now.minus(STALE_AFTER));
      boolean retry = retryFailed && (run.getStatus() == AutomationRunStatus.FAILED
          || run.getStatus() == AutomationRunStatus.SKIPPED);
      if (!stale && !retry) return Optional.empty();
      run.queued(postMode, now);
      return Optional.of(run.getId());
    }
    try {
      AutomationRun created = runs.saveAndFlush(new AutomationRun(
          UUID.randomUUID(), projectId, kind, trigger, ref, pr.headSha(), pr.title(),
          pr.webUrl(), postMode, now));
      // A draft for an older commit would be stale by the time somebody approves it.
      runs.findByProjectIdAndKindAndExternalRefOrderByCreatedAtDesc(projectId, kind, ref).stream()
          .filter(other -> other.getStatus() == AutomationRunStatus.AWAITING_APPROVAL
              && !other.getHeadSha().equals(pr.headSha()))
          .forEach(other -> other.skipped(
              "Superseded by newer commits; the new head was reviewed instead.", now));
      return Optional.of(created.getId());
    } catch (DataIntegrityViolationException raced) {
      return Optional.empty();
    }
  }

  /**
   * A queued or running review of this head that nobody is working on any more (the process was
   * restarted), so it should be picked up again even though the pull request itself did not change.
   */
  @Transactional(readOnly = true)
  public boolean isStaleActive(
      UUID projectId, AutomationKind kind, long number, String headSha, Set<UUID> inFlight) {
    return runs.findByProjectIdAndKindAndExternalRefAndHeadSha(
            projectId, kind, Long.toString(number), headSha)
        .filter(run -> run.getStatus().active() && !inFlight.contains(run.getId())
            && run.getUpdatedAt().isBefore(Instant.now().minus(STALE_AFTER)))
        .isPresent();
  }

  @Transactional(readOnly = true)
  public AutomationRun require(UUID id) {
    return runs.findById(id).orElseThrow(() -> new IllegalArgumentException("Unknown automation run"));
  }

  @Transactional(readOnly = true)
  public List<AutomationRun> recent(UUID projectId) {
    return runs.findTop20ByProjectIdOrderByCreatedAtDesc(projectId);
  }

  @Transactional
  public void running(UUID id) { load(id).running(Instant.now()); }

  @Transactional
  public void workspace(UUID id, UUID worktreeId, List<String> changedFiles) {
    load(id).workspace(worktreeId, String.join("\n", changedFiles), Instant.now());
  }

  @Transactional
  public void agentRun(UUID id, UUID agentRunId) { load(id).agentRun(agentRunId, Instant.now()); }

  @Transactional
  public void review(UUID id, String markdown, String json) {
    load(id).review(markdown, json, Instant.now());
  }

  /** The newest earlier review of the same pull request for a different head commit. */
  @Transactional(readOnly = true)
  public Optional<AutomationRun> previousReview(UUID id) {
    AutomationRun run = load(id);
    return runs.findByProjectIdAndKindAndExternalRefOrderByCreatedAtDesc(
            run.getProjectId(), run.getKind(), run.getExternalRef()).stream()
        .filter(other -> !other.getId().equals(id) && other.getReviewJson() != null
            && !other.getHeadSha().equals(run.getHeadSha())
            && other.getCreatedAt().isBefore(run.getCreatedAt()))
        .findFirst();
  }

  @Transactional
  public void awaitingApproval(UUID id) { load(id).awaitingApproval(Instant.now()); }

  @Transactional
  public void posted(UUID id, String commentUrl) { load(id).posted(commentUrl, Instant.now()); }

  @Transactional
  public void skipped(UUID id, String reason) { load(id).skipped(reason, Instant.now()); }

  @Transactional
  public void failed(UUID id, String reason) { load(id).failed(reason, Instant.now()); }

  private AutomationRun load(UUID id) {
    return runs.findById(id).orElseThrow(() -> new IllegalArgumentException("Unknown automation run"));
  }
}
