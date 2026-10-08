package tech.wenisch.petri.agent.run;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;
import tech.wenisch.petri.config.PetriProperties;

@Component
class TestCommandRunner {
  private static final String[] ALLOWED_ENVIRONMENT = {
      "PATH", "JAVA_HOME", "HOME", "USER", "TMPDIR", "SystemRoot", "ComSpec"
  };

  private final Duration timeout;
  private final int outputLimit;

  TestCommandRunner(PetriProperties properties) {
    this.timeout = properties.workspace().testCommandTimeout();
    this.outputLimit = properties.workspace().maxTestOutputBytes();
  }

  TestCommandResult run(Path worktree, TestCommand command) throws IOException {
    ProcessBuilder builder = new ProcessBuilder(command.arguments())
        .directory(worktree.toFile())
        .redirectErrorStream(true);
    sanitizeEnvironment(builder.environment());

    Process process = builder.start();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<CapturedOutput> output = executor.submit(() -> capture(process.getInputStream()));
      boolean finished;
      try {
        finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        terminate(process);
        throw new IOException("Interrupted while waiting for test command", exception);
      }
      if (!finished) terminate(process);
      CapturedOutput captured = readOutput(output);
      return new TestCommandResult(
          finished ? process.exitValue() : -1,
          captured.value(),
          !finished,
          captured.truncated());
    }
  }

  private CapturedOutput capture(InputStream input) throws IOException {
    ByteArrayOutputStream retained = new ByteArrayOutputStream(Math.min(outputLimit, 8192));
    byte[] buffer = new byte[8192];
    long total = 0;
    int read;
    while ((read = input.read(buffer)) >= 0) {
      int writable = Math.min(read, Math.max(0, outputLimit - retained.size()));
      if (writable > 0) retained.write(buffer, 0, writable);
      total += read;
    }
    return new CapturedOutput(retained.toString(StandardCharsets.UTF_8), total > outputLimit);
  }

  private static CapturedOutput readOutput(Future<CapturedOutput> output) throws IOException {
    try {
      return output.get(5, TimeUnit.SECONDS);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while reading test output", exception);
    } catch (ExecutionException exception) {
      throw new IOException("Could not read test output", exception.getCause());
    } catch (TimeoutException exception) {
      throw new IOException("Test output stream did not close", exception);
    }
  }

  private static void sanitizeEnvironment(Map<String, String> environment) {
    Map<String, String> inherited = Map.copyOf(environment);
    environment.clear();
    for (String name : ALLOWED_ENVIRONMENT) {
      String value = inherited.get(name);
      if (value != null) environment.put(name, value);
    }
    environment.put("CI", "true");
    environment.put("LC_ALL", "C");
  }

  private static void terminate(Process process) {
    List<ProcessHandle> descendants = process.descendants().toList();
    descendants.forEach(ProcessHandle::destroy);
    process.destroy();
    try {
      process.waitFor(1, TimeUnit.SECONDS);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
    }
    descendants.stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
    if (process.isAlive()) process.destroyForcibly();
  }

  private record CapturedOutput(String value, boolean truncated) {}
}
