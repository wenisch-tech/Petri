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
      @Value("${petri.automation.enabled:true}") boolean enabled,
      @Value("${petri.automation.max-per-poll:5}") int maxPerPoll,
      @Value("${petri.automation.max-concurrent:2}") int maxConcurrent) {
    this.automations = automations;
    this.settings = settings;
    this.runs = runs;
    this.sources = sources;
    this.connections = connections;
    this.review = review;
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

  /** Manual trigger from the UI; returns how many reviews were queued. */
  public int runNow(ProjectAutomation automation) {
    return queue(automation, true, true);
  }

  private int queue(ProjectAutomation automation, boolean includeAll, boolean retryFailed) {
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
            || pr.updatedAt().isAfter(baseline))
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
          automation.getProviderInstanceId(), automation.getModel(), automation.getMaxModelTurns()));
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
