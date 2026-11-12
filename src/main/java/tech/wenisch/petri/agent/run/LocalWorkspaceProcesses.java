package tech.wenisch.petri.agent.run;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tech.wenisch.petri.workspace.WorkspaceProcesses;

/** Runs processes in Petri's own container with the shared bounded-process runner. */
@Component
public class LocalWorkspaceProcesses implements WorkspaceProcesses {
  private final LocalToolchain toolchain;
  private final boolean localExecutor;

  public LocalWorkspaceProcesses(
      LocalToolchain toolchain, @Value("${petri.tests.executor:podman}") String executor) {
    this.toolchain = toolchain;
    this.localExecutor = "local".equalsIgnoreCase(executor);
  }

  @Override
  public TestCommandResult exec(
      Path cwd, List<String> argv, Map<String, String> environment, Duration timeout,
      int outputLimit, String digest, Consumer<String> progress) throws IOException {
    return BoundedProcess.run(argv, cwd, environment, timeout, outputLimit, digest, progress);
  }

  @Override
  public boolean hasTool(String name) {
    return localExecutor && toolchain.hasTool(name);
  }
}
