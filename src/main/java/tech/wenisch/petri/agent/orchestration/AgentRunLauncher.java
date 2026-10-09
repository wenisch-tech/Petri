package tech.wenisch.petri.agent.orchestration;

import jakarta.annotation.PreDestroy;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class AgentRunLauncher {
  private static final Logger log = LoggerFactory.getLogger(AgentRunLauncher.class);

  private final AgentRunExecutor executor;
  private final ExecutorService tasks = Executors.newVirtualThreadPerTaskExecutor();

  public AgentRunLauncher(AgentRunExecutor executor) {
    this.executor = executor;
  }

  public void launch(UUID runId, UUID worktreeId, UUID creatorId, String actor) {
    tasks.submit(() -> {
      try {
        executor.execute(runId, worktreeId, creatorId, actor);
      } catch (RuntimeException exception) {
        log.warn("Agent run {} could not be launched: {}", runId, exception.getMessage());
      }
    });
  }

  @PreDestroy
  void close() {
    tasks.shutdownNow();
  }
}
