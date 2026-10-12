package tech.wenisch.petri.git;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tech.wenisch.petri.config.PetriProperties;
import tech.wenisch.petri.config.TlsSettings;
import tech.wenisch.petri.workspace.WorkspacePaths;

@Component
public class GitCommandRunner {
  private static final Pattern URL_USER_INFO =
      Pattern.compile("(?i)(https?://)[^/@\\s]+@");

  private final WorkspacePaths paths;
  private final Duration timeout;
  private final int outputLimit;
  private final boolean ignoreTls;

  @Autowired
  public GitCommandRunner(WorkspacePaths paths, PetriProperties properties, TlsSettings tls) {
    this.paths = paths;
    this.ignoreTls = tls.ignoreTls();
    this.timeout = properties.workspace().gitCommandTimeout();
    this.outputLimit = properties.workspace().maxCommandOutputBytes();
  }

  public GitCommandRunner(WorkspacePaths paths, PetriProperties properties) {
    this(paths, properties, TlsSettings.verifying());
  }

  GitCommandResult run(Path workingDirectory, List<String> arguments) throws IOException {
    return run(workingDirectory, arguments, Map.of(), List.of());
  }

  GitCommandResult run(
      Path workingDirectory,
      List<String> arguments,
      Map<String, String> environment,
      Collection<String> secrets) throws IOException {
    Path safeWorkingDirectory = paths.requireExistingPath(workingDirectory);
    requireSafeArguments(arguments, secrets);
    List<String> command = new ArrayList<>(arguments.size() + 1);
    command.add("git");
    command.addAll(arguments);

    ProcessBuilder builder = new ProcessBuilder(command)
        .directory(safeWorkingDirectory.toFile())
        .redirectErrorStream(true);
    Map<String, String> processEnvironment = builder.environment();
    processEnvironment.put("GIT_TERMINAL_PROMPT", "0");
    processEnvironment.put("GIT_CONFIG_NOSYSTEM", "1");
    processEnvironment.put("GIT_CONFIG_GLOBAL", platformNullDevice());
    processEnvironment.put("LC_ALL", "C");
    if (ignoreTls) processEnvironment.put("GIT_SSL_NO_VERIFY", "true");
    processEnvironment.putAll(environment);

    Process process = builder.start();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<CapturedOutput> output = executor.submit(() -> capture(process.getInputStream()));
      boolean finished;
      try {
        finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        terminate(process);
        throw new IOException("Interrupted while waiting for Git", exception);
      }
      if (!finished) terminate(process);
      CapturedOutput captured = readOutput(output);
      int exitCode = finished ? process.exitValue() : -1;
      return new GitCommandResult(
          exitCode,
          redact(captured.value(), secrets),
          !finished,
          captured.truncated());
    }
  }

  private CapturedOutput capture(InputStream input) throws IOException {
    ByteArrayOutputStream retained = new ByteArrayOutputStream(Math.min(outputLimit, 8192));
    byte[] buffer = new byte[8192];
    int total = 0;
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
      throw new IOException("Interrupted while reading Git output", exception);
    } catch (ExecutionException exception) {
      throw new IOException("Could not read Git output", exception.getCause());
    } catch (TimeoutException exception) {
      throw new IOException("Git output stream did not close", exception);
    }
  }

  private static void terminate(Process process) {
    process.destroy();
    try {
      if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      process.destroyForcibly();
    }
  }

  private static void requireSafeArguments(List<String> arguments, Collection<String> secrets) {
    if (arguments == null || arguments.isEmpty()) {
      throw new IllegalArgumentException("A Git subcommand is required");
    }
    if (arguments.stream().anyMatch(argument -> argument == null || argument.indexOf('\0') >= 0)) {
      throw new IllegalArgumentException("Git arguments must not be null or contain NUL bytes");
    }
    for (String secret : secrets) {
      if (secret != null && !secret.isEmpty()
          && arguments.stream().anyMatch(argument -> argument.contains(secret))) {
        throw new IllegalArgumentException("Secrets must not be passed as Git command arguments");
      }
    }
  }

  private static String redact(String output, Collection<String> secrets) {
    String sanitized = URL_USER_INFO.matcher(output).replaceAll("$1***@");
    for (String secret : secrets) {
      if (secret != null && !secret.isEmpty()) sanitized = sanitized.replace(secret, "********");
    }
    return sanitized;
  }

  private static String platformNullDevice() {
    return System.getProperty("os.name", "").toLowerCase().contains("win") ? "NUL" : "/dev/null";
  }

  private record CapturedOutput(String value, boolean truncated) {}
}
