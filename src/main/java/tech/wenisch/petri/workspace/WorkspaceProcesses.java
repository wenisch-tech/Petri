package tech.wenisch.petri.workspace;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import tech.wenisch.petri.agent.run.TestCommandResult;

/** Runs processes on the host: explicit environment only, a deadline and bounded output. */
public interface WorkspaceProcesses {
  TestCommandResult exec(
      Path cwd, List<String> argv, Map<String, String> environment, Duration timeout,
      int outputLimit, String digest, Consumer<String> progress) throws IOException;

  /** Whether an executable of that name is available to commands on this host. */
  boolean hasTool(String name);
}
