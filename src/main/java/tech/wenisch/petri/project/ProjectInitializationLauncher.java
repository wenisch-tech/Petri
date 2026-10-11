package tech.wenisch.petri.project;

import jakarta.annotation.PreDestroy;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import tech.wenisch.petri.git.ProjectMirrorService;
import tech.wenisch.petri.security.AppUserRepository;

@Component
public class ProjectInitializationLauncher {
  private final ProjectInitializationStateService state;
  private final ProjectRepository projects;
  private final AppUserRepository users;
  private final ProjectMirrorService mirrors;
  private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
  private final ScheduledExecutorService leaseHeartbeat = Executors.newSingleThreadScheduledExecutor(
      Thread.ofPlatform().daemon().name("petri-import-lease").factory());
  private final Semaphore capacity;
  private final String leaseOwner = "petri-" + UUID.randomUUID();

  public ProjectInitializationLauncher(
      ProjectInitializationStateService state,
      ProjectRepository projects,
      AppUserRepository users,
      ProjectMirrorService mirrors,
      @Value("${petri.imports.max-parallel:4}") int maxParallel) {
    this.state = state;
    this.projects = projects;
    this.users = users;
    this.mirrors = mirrors;
    this.capacity = new Semaphore(Math.max(1, maxParallel));
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void requested(ProjectInitializationRequested event) {
    launch(event.jobId());
  }

  @Scheduled(initialDelay = 5_000, fixedDelay = 30_000)
  public void recover() {
    state.recoverable().forEach(job -> launch(job.getId()));
  }

  public void launch(UUID jobId) {
    executor.submit(() -> execute(jobId));
  }

  public void retry(UUID projectId) {
    launch(state.retry(projectId).getId());
  }

  private void execute(UUID jobId) {
    boolean acquired = false;
    boolean claimed = false;
    ScheduledFuture<?> heartbeat = null;
    String executionOwner = leaseOwner + ":" + UUID.randomUUID();
    try {
      capacity.acquire();
      acquired = true;
      ProjectInitializationJob job = state.claim(jobId, executionOwner);
      if (job == null) return;
      claimed = true;
      heartbeat = leaseHeartbeat.scheduleAtFixedRate(
          () -> renewLease(jobId, executionOwner), 1, 1, TimeUnit.MINUTES);
      Project project = projects.findById(job.getProjectId())
          .orElseThrow(() -> new IllegalArgumentException("Project not found"));
      String actorEmail = users.findById(project.getCreatedBy())
          .orElseThrow(() -> new IllegalArgumentException("Project owner not found"))
          .getEmail();
      mirrors.synchronize(project.getId(), actorEmail);
      state.complete(jobId, executionOwner);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      if (claimed) state.fail(jobId, executionOwner, "Initialization was interrupted");
    } catch (RuntimeException exception) {
      if (claimed) state.fail(jobId, executionOwner, safeMessage(exception));
    } finally {
      if (heartbeat != null) heartbeat.cancel(false);
      if (acquired) capacity.release();
    }
  }

  private void renewLease(UUID jobId, String executionOwner) {
    try {
      state.renew(jobId, executionOwner);
    } catch (RuntimeException ignored) {
      // A transient heartbeat failure must not terminate the mirror operation. The next
      // heartbeat can recover, while owner-checked completion prevents a stale worker from
      // overwriting a job that another process has reclaimed.
    }
  }

  private static String safeMessage(RuntimeException exception) {
    String message = exception.getMessage();
    if (message == null || message.isBlank()) return "Mirror initialization failed";
    return message.substring(0, Math.min(2000, message.length()));
  }

  @PreDestroy
  void shutdown() {
    executor.close();
    leaseHeartbeat.shutdownNow();
  }
}
