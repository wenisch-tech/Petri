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

/** Runs a process without a shell, with an explicit environment, a deadline and bounded output. */
final class BoundedProcess {
  private BoundedProcess() {}

  static TestCommandResult run(
      List<String> command, Path directory, Map<String, String> environment,
      Duration timeout, int outputLimit, String digest) throws IOException {
    ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile())
        .redirectErrorStream(true);
    builder.environment().clear();
    builder.environment().putAll(environment);
    Process process = builder.start();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<Captured> output = executor.submit(() -> capture(process.getInputStream(), outputLimit));
      boolean finished;
      try {
        finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        kill(process);
        throw new IOException("Interrupted while waiting for command", exception);
      }
      if (!finished) kill(process);
      Captured captured = read(output);
      return new TestCommandResult(finished ? process.exitValue() : -1, captured.value(),
          !finished, captured.truncated(), digest);
    }
  }

  private static void kill(Process process) {
    process.descendants().forEach(ProcessHandle::destroyForcibly);
    process.destroyForcibly();
  }

  private static Captured capture(InputStream input, int limit) throws IOException {
    ByteArrayOutputStream retained = new ByteArrayOutputStream(Math.min(limit, 8192));
    byte[] buffer = new byte[8192];
    long total = 0;
    int read;
    while ((read = input.read(buffer)) >= 0) {
      int writable = Math.min(read, Math.max(0, limit - retained.size()));
      if (writable > 0) retained.write(buffer, 0, writable);
      total += read;
    }
    return new Captured(retained.toString(StandardCharsets.UTF_8), total > limit);
  }

  private static Captured read(Future<Captured> output) throws IOException {
    try {
      return output.get(10, TimeUnit.SECONDS);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while reading command output", exception);
    } catch (ExecutionException exception) {
      throw new IOException("Could not read command output", exception.getCause());
    } catch (TimeoutException exception) {
      throw new IOException("Command output stream did not close", exception);
    }
  }

  private record Captured(String value, boolean truncated) {}
}
