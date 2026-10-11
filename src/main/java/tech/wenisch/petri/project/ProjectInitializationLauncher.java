package tech.wenisch.petri.project;

import jakarta.annotation.PreDestroy;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
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
    try {
      capacity.acquire();
      acquired = true;
      ProjectInitializationJob job = state.claim(jobId, leaseOwner);
      if (job == null) return;
      Project project = projects.findById(job.getProjectId())
          .orElseThrow(() -> new IllegalArgumentException("Project not found"));
      String actorEmail = users.findById(project.getCreatedBy())
          .orElseThrow(() -> new IllegalArgumentException("Project owner not found"))
          .getEmail();
      mirrors.synchronize(project.getId(), actorEmail);
      state.complete(jobId);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      state.fail(jobId, "Initialization was interrupted");
    } catch (RuntimeException exception) {
      state.fail(jobId, safeMessage(exception));
    } finally {
      if (acquired) capacity.release();
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
  }
}
