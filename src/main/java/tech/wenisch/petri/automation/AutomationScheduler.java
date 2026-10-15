package tech.wenisch.petri.automation;

import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tech.wenisch.petri.git.provider.GitAccountConnectionService;
import tech.wenisch.petri.git.provider.ProjectProviderSource;
import tech.wenisch.petri.git.provider.ProjectProviderSourceService;
import tech.wenisch.petri.git.provider.PullRequestListing;
import tech.wenisch.petri.git.provider.PullRequestRef;

/**
 * Starts automations: polls pull requests of projects with the pull request trigger and fires the
 * cron trigger. Listing is cheap and happens on the scheduler thread; the reviews themselves run on
 * a bounded pool, one at a time per project.
 */
@Component
public class AutomationScheduler {
  private static final Logger log = LoggerFactory.getLogger(AutomationScheduler.class);

  private final ProjectAutomationRepository automations;
  private final AutomationSettingsService settings;
  private final AutomationRunService runs;
  private final ProjectProviderSourceService sources;
  private final GitAccountConnectionService connections;
  private final CodeReviewAutomation review;
  private final PullRequestImprovementService improvements;
  private final VulnerabilityFixService fixes;
  private final boolean enabled;
  private final int maxPerPoll;
  private final Semaphore concurrency;
  private final ExecutorService tasks = Executors.newVirtualThreadPerTaskExecutor();
  private final Set<UUID> inFlight = ConcurrentHashMap.newKeySet();
  private final ConcurrentHashMap<UUID, ReentrantLock> projectLocks = new ConcurrentHashMap<>();

  AutomationScheduler(
      ProjectAutomationRepository automations, AutomationSettingsService settings,
      AutomationRunService runs, ProjectProviderSourceService sources,
      GitAccountConnectionService connections, CodeReviewAutomation review,
      PullRequestImprovementService improvements, VulnerabilityFixService fixes,
      @Value("${petri.automation.enabled:true}") boolean enabled,
      @Value("${petri.automation.max-per-poll:5}") int maxPerPoll,
      @Value("${petri.automation.max-concurrent:2}") int maxConcurrent) {
    this.automations = automations;
    this.settings = settings;
    this.runs = runs;
    this.sources = sources;
    this.connections = connections;
    this.review = review;
    this.improvements = improvements;
    this.fixes = fixes;
    this.enabled = enabled;
    this.maxPerPoll = Math.max(1, maxPerPoll);
    this.concurrency = new Semaphore(Math.max(1, maxConcurrent));
  }

  @Scheduled(
      initialDelayString = "${petri.automation.initial-delay-ms:30000}",
      fixedDelayString = "${petri.automation.poll-interval-ms:120000}")
  void pollPullRequests() {
    if (!enabled) return;
    for (ProjectAutomation automation : automations.findAllByEnabledTrue()) {
      if (automation.getTrigger() != AutomationTrigger.PULL_REQUEST) continue;
      try {
        queue(automation, false, false);
      } catch (RuntimeException exception) {
        log.warn("Pull request poll for project {} failed: {}",
            automation.getProjectId(), exception.getMessage());
      }
    }
  }

  @Scheduled(initialDelay = 60_000, fixedDelay = 15_000)
  void advanceImprovements() {
    if (!enabled) return;
    try {
      improvements.advanceAll();
      fixes.advanceAll();
    } catch (RuntimeException exception) {
      log.warn("Advancing improvements failed: {}", exception.getMessage());
    }
  }

  @Scheduled(initialDelay = 45_000, fixedDelay = 60_000)
  void fireCron() {
    if (!enabled) return;
    Instant now = Instant.now();
    for (ProjectAutomation automation : automations.findAllByEnabledTrue()) {
      if (automation.getTrigger() != AutomationTrigger.CRON) continue;
      try {
        Instant base = automation.getLastCronAt() != null
            ? automation.getLastCronAt() : automation.getUpdatedAt();
        Instant next = AutomationSettingsService.nextFire(automation.getCronExpression(), base);
        if (next == null || next.isAfter(now)) continue;
        settings.markCronFired(automation.getId(), now);
        queue(automation, true, false);
      } catch (RuntimeException exception) {
        log.warn("Scheduled automation for project {} failed: {}",
            automation.getProjectId(), exception.getMessage());
      }
    }
  }

  /**
   * Runs one review automation on one pull request because a person asked for it from the pull
   * request. Uses the project's saved model settings for that automation when it has any. Drafts
   * are allowed and a head that was already reviewed is reviewed again.
   *
   * @return the automation run, which shows its progress on the project's Automations tab
   */
  public UUID runOnDemand(
      UUID projectId, AutomationKind kind, long number, AutomationPostMode postMode) {
    if (kind.branchBased()) {
      throw new IllegalStateException(kind.title() + " works on a branch, not on a pull request");
    }
    ProjectProviderSource source = sources.find(projectId);
    if (source == null) {
      throw new IllegalStateException(
          "The project is not linked to a connected Git account, so there is no pull request to work on");
    }
    PullRequestRef pr = connections.openPullRequests(
            source.getConnectionId(), source.getExternalRepositoryId(), source.getFullName())
        .pullRequests().stream().filter(value -> value.number() == number).findFirst()
        .orElseThrow(() -> new IllegalStateException(
            "Pull request #" + number + " is closed or merged"));
    ProjectAutomation saved = automations.findByProjectIdAndKindAndTrigger(
            projectId, kind, AutomationTrigger.PULL_REQUEST)
        .or(() -> automations.findByProjectIdAndKindAndTrigger(
            projectId, kind, AutomationTrigger.CRON)).orElse(null);
    UUID provider = saved == null ? null : saved.getProviderInstanceId();
    String model = saved == null ? null : saved.getModel();
    int turns = saved == null ? 20 : saved.getMaxModelTurns();
    Optional<UUID> runId = runs.register(projectId, kind, AutomationTrigger.MANUAL, pr, postMode,
        true, true, inFlight);
    if (runId.isEmpty()) {
      throw new IllegalStateException("This pull request is already being worked on");
    }
    submit(new CodeReviewAutomation.Job(
        runId.get(), projectId, pr, postMode, provider, model, turns, kind, true));
    return runId.get();
  }

  /** Runs a branch-based action on a branch because a person asked for it. */
  public UUID runOnBranch(
      UUID projectId, AutomationKind kind, String branch, AutomationPostMode postMode) {
    if (!kind.branchBased()) {
      throw new IllegalStateException(kind.title() + " works on a pull request, not on a branch");
    }
    return fixes.start(projectId, kind, AutomationTrigger.MANUAL, branch, postMode);
  }

  /** Manual trigger from the UI; returns how many reviews were queued. */
  public int runNow(ProjectAutomation automation) {
    return queue(automation, true, true);
  }

  /**
   * Starts a failed or skipped review of one pull request again, with the pull request as it is
   * now: when it received new commits since, the new head is reviewed instead.
   */
  public void retry(ProjectAutomation automation, AutomationRun failed) {
    if (automation.getKind().branchBased()) {
      fixes.start(automation);
      return;
    }
    UUID projectId = automation.getProjectId();
    ProjectProviderSource source = sources.find(projectId);
    if (source == null) {
      throw new IllegalStateException("The project is no longer linked to a connected Git account");
    }
    long number = Long.parseLong(failed.getExternalRef());
    PullRequestRef pr = connections.openPullRequests(
            source.getConnectionId(), source.getExternalRepositoryId(), source.getFullName())
        .pullRequests().stream().filter(value -> value.number() == number).findFirst()
        .orElseThrow(() -> new IllegalStateException(
            "Pull request #" + number + " is closed or merged"));
    if (pr.draft()) {
      throw new IllegalStateException("Pull request #" + number
          + " is a draft; it is reviewed once it is ready");
    }
    Optional<UUID> runId = runs.register(projectId, automation.getKind(), automation.getTrigger(),
        pr, automation.getPostMode(), true, inFlight);
    if (runId.isEmpty()) {
      throw new IllegalStateException("This review is already queued or running");
    }
    submit(new CodeReviewAutomation.Job(runId.get(), projectId, pr, automation.getPostMode(),
        automation.getProviderInstanceId(), automation.getModel(), automation.getMaxModelTurns(),
        automation.getKind()));
  }

  private int queue(ProjectAutomation automation, boolean includeAll, boolean retryFailed) {
    if (automation.getKind().branchBased()) {
      // Works on its selected branch, not on pull requests: one run per start.
      fixes.start(automation);
      return 1;
    }
    UUID projectId = automation.getProjectId();
    ProjectProviderSource source = sources.find(projectId);
    if (source == null) return 0;
    PullRequestListing listing = connections.openPullRequests(
        source.getConnectionId(), source.getExternalRepositoryId(), source.getFullName());
    Instant now = Instant.now();
    Instant baseline = automation.getLastPolledAt();
    int queued = 0;
    boolean deferred = false;
    List<PullRequestRef> candidates = listing.pullRequests().stream()
        .filter(pr -> !pr.draft())
        .filter(pr -> includeAll || baseline == null || pr.updatedAt() == null
            || pr.updatedAt().isAfter(baseline)
            // A review interrupted by a restart is resumed even if the pull request is unchanged.
            || runs.isStaleActive(projectId, automation.getKind(), pr.number(), pr.headSha(), inFlight))
        .toList();
    for (PullRequestRef pr : candidates) {
      if (queued >= maxPerPoll) {
        deferred = true;
        break;
      }
      Optional<UUID> runId = runs.register(
          projectId, automation.getKind(), includeAll ? AutomationTrigger.CRON : automation.getTrigger(),
          pr, automation.getPostMode(), retryFailed, inFlight);
      if (runId.isEmpty()) continue;
      submit(new CodeReviewAutomation.Job(runId.get(), projectId, pr, automation.getPostMode(),
          automation.getProviderInstanceId(), automation.getModel(), automation.getMaxModelTurns(),
        automation.getKind()));
      queued++;
    }
    // A capped poll keeps its baseline so the remaining pull requests are picked up next time.
    if (!deferred && automation.getTrigger() == AutomationTrigger.PULL_REQUEST) {
      settings.markPolled(automation.getId(), now);
    }
    return queued;
  }

  private void submit(CodeReviewAutomation.Job job) {
    inFlight.add(job.runId());
    tasks.submit(() -> {
      ReentrantLock lock = projectLocks.computeIfAbsent(job.projectId(), ignored -> new ReentrantLock());
      lock.lock();
      try {
        concurrency.acquire();
        try {
          review.execute(job);
        } finally {
          concurrency.release();
        }
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
      } catch (RuntimeException exception) {
        log.warn("Automation run {} aborted", job.runId(), exception);
      } finally {
        lock.unlock();
        inFlight.remove(job.runId());
      }
    });
  }

  @PreDestroy
  void close() {
    tasks.shutdownNow();
  }
}
