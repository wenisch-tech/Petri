package tech.wenisch.petri.kubernetes;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import tech.wenisch.petri.agent.run.TestCommandResult;
import tech.wenisch.petri.agent.tool.FileConflictException;
import tech.wenisch.petri.agent.tool.ListFilesResult;
import tech.wenisch.petri.agent.tool.ReadFileResult;
import tech.wenisch.petri.agent.tool.ReplacementPreview;
import tech.wenisch.petri.agent.tool.SearchFilesResult;
import tech.wenisch.petri.agent.tool.WorkspaceEntryMutation;
import tech.wenisch.petri.agent.tool.WorkspaceFileService;
import tech.wenisch.petri.config.PetriProperties;
import tech.wenisch.petri.config.TlsSettings;
import tech.wenisch.petri.git.GitAuthenticationType;
import tech.wenisch.petri.git.GitCommandResult;
import tech.wenisch.petri.git.GitCommandRunner;
import tech.wenisch.petri.git.GitCredentialMaterial;
import tech.wenisch.petri.kubernetes.ClusterGateway.AgentResponse;
import tech.wenisch.petri.workspace.HostStat;
import tech.wenisch.petri.workspace.RawFile;
import tech.wenisch.petri.workspace.WorkspaceArchives;
import tech.wenisch.petri.workspace.WorkspaceFiles;
import tech.wenisch.petri.workspace.WorkspaceGit;
import tech.wenisch.petri.workspace.WorkspaceHost;
import tech.wenisch.petri.workspace.WorkspaceHostKind;
import tech.wenisch.petri.workspace.WorkspaceLayout;
import tech.wenisch.petri.workspace.WorkspaceProcesses;
import tools.jackson.databind.JsonNode;

/** The workspace host of a project whose mirror, worktrees and commands live in a Kubernetes pod. */
public class RemoteWorkspaceHost implements WorkspaceHost {
  private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
  private static final Pattern TOOL = Pattern.compile("[A-Za-z0-9][A-Za-z0-9+._-]{0,63}");
  private static final Duration IDLE_STREAM = Duration.ofSeconds(90);

  private final Path root;
  private final UUID projectId;
  private final PodSessions orchestrator;
  private final ClusterGateway gateway;
  private final PetriProperties.Workspace limits;
  private final boolean ignoreTls;
  private final Set<String> ensured = ConcurrentHashMap.newKeySet();
  private final Map<String, Boolean> tools = new ConcurrentHashMap<>();
  private final Layout layout = new Layout();
  private final Files_ files = new Files_();
  private final Processes processes = new Processes();
  private final Git git = new Git();
  private final Archives archives = new Archives();

  public RemoteWorkspaceHost(
      UUID projectId, PodSessions orchestrator, ClusterGateway gateway,
      PetriProperties properties, TlsSettings tls) {
    this(projectId, orchestrator, gateway, properties, tls, Path.of(WorkspacePodSpecs.WORKSPACE_ROOT));
  }

  /** As above with the workspace root the agent serves; only tests use another one. */
  RemoteWorkspaceHost(
      UUID projectId, PodSessions orchestrator, ClusterGateway gateway,
      PetriProperties properties, TlsSettings tls, Path root) {
    this.root = root;
    this.projectId = projectId;
    this.orchestrator = orchestrator;
    this.gateway = gateway;
    this.limits = properties.workspace();
    this.ignoreTls = tls.ignoreTls();
  }

  @Override public WorkspaceHostKind kind() { return WorkspaceHostKind.KUBERNETES; }
  @Override public UUID projectId() { return projectId; }
  @Override public WorkspaceLayout layout() { return layout; }
  @Override public WorkspaceFiles files() { return files; }
  @Override public WorkspaceProcesses processes() { return processes; }
  @Override public WorkspaceGit git() { return git; }
  @Override public WorkspaceArchives archives() { return archives; }

  // ---- plumbing ----------------------------------------------------------------------------

  private interface Call<T> {
    T run(AgentClient client) throws IOException;
  }

  private <T> T with(Call<T> call) {
    try {
      return withIo(call);
    } catch (IOException exception) {
      throw new IllegalStateException("The workspace pod could not be used: "
          + FabricClusterGateway.brief(exception), exception);
    }
  }

  private <T> T withIo(Call<T> call) throws IOException {
    try {
      return once(call);
    } catch (WorkspaceHostUnavailableException exception) {
      if (!exception.isPodGone()) throw exception;
      // Someone deleted the pod (or the node lost it). The request never arrived, so start the
      // pod again and repeat it once.
      orchestrator.forget(projectId);
      return once(call);
    }
  }

  private <T> T once(Call<T> call) throws IOException {
    try (var session = orchestrator.use(projectId)) {
      return call.run(new AgentClient(gateway, session.handle()));
    }
  }

  private static String q(String value) {
    return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
  }

  private static String text(Path path) {
    return path.toString().replace('\\', '/');
  }

  // ---- layout ------------------------------------------------------------------------------

  private final class Layout implements WorkspaceLayout {
    private Path directory(String... segments) throws IOException {
      Path current = root;
      for (String segment : segments) {
        if (segment.isBlank() || segment.contains("/") || segment.equals("..") || segment.equals(".")) {
          throw new IllegalArgumentException("Invalid workspace path segment");
        }
        current = current.resolve(segment);
      }
      String key = text(current);
      if (!ensured.contains(key)) {
        withIo(client -> client.json("POST", "/mkdir", Map.of("path", key)));
        ensured.add(key);
      }
      return current;
    }

    @Override public Path root() { return root; }

    @Override
    public Path mirror(UUID id) throws IOException {
      return directory("mirrors", id + ".git");
    }

    @Override
    public Path runWorktree(UUID project, UUID worktreeId) throws IOException {
      return directory("runs", project.toString()).resolve(worktreeId.toString());
    }

    @Override public Path temporaryDirectory() throws IOException { return directory("tmp"); }

    @Override
    public Path worktreeTemporaryDirectory(Path worktree) throws IOException {
      String name = worktree.getFileName().toString();
      UUID.fromString(name);
      return directory("worktree-tmp", name);
    }

    @Override
    public Path testCaches(UUID id) throws IOException {
      return directory("test-caches", id.toString());
    }

    @Override
    public Path createTemporaryDirectory(String prefix) throws IOException {
      JsonNode answer = withIo(client -> client.json("POST", "/tmpdir", Map.of("prefix", prefix)));
      return Path.of(answer.path("path").asString());
    }

    @Override
    public void deleteTemporary(Path path) {
      try {
        withIo(client -> client.json("POST", "/rm", Map.of("path", text(path))));
      } catch (IOException | RuntimeException ignored) {
        // scratch space only; a leftover directory is harmless
      }
    }

    @Override
    public void removeWorktreeTemporaryDirectory(UUID worktreeId) {
      deleteTemporary(root.resolve("worktree-tmp").resolve(worktreeId.toString()));
    }

    @Override
    public Path requireExistingPath(Path path) {
      // The agent validates every operation against the workspace root; a round trip per call here
      // would only repeat that.
      Path normalized = path.normalize();
      if (!normalized.isAbsolute() || !normalized.startsWith(root)) {
        throw new IllegalArgumentException("Path is outside the Petri workspace");
      }
      return normalized;
    }

    @Override
    public HostStat stat(Path path) throws IOException {
      JsonNode node = withIo(client -> client.json("GET", "/stat?path=" + q(text(path)), null));
      if (!node.path("exists").asBoolean(false)) return HostStat.missing();
      return new HostStat(true, HostStat.Type.valueOf(node.path("type").asString("OTHER")),
          node.path("size").asLong(0), node.path("executable").asBoolean(false));
    }

    @Override
    public boolean isEmptyDirectory(Path directory) throws IOException {
      return withIo(client -> client.json("GET", "/empty?path=" + q(text(directory)), null))
          .path("empty").asBoolean(false);
    }
  }

  // ---- files -------------------------------------------------------------------------------

  private final class Files_ implements WorkspaceFiles {
    private String limitQuery() {
      return "&maxFileBytes=" + limits.maxToolFileBytes() + "&maxOutputBytes=" + limits.maxToolOutputBytes()
          + "&maxSearchResults=" + limits.maxSearchResults();
    }

    private Map<String, Object> body(Path worktree) {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("root", text(worktree));
      body.put("maxFileBytes", limits.maxToolFileBytes());
      body.put("maxOutputBytes", limits.maxToolOutputBytes());
      return body;
    }

    private <T> T parse(JsonNode node, Class<T> type) {
      return AgentClient.JSON.treeToValue(node, type);
    }

    @Override
    public ListFilesResult list(Path worktree, String requestedPath, int maxDepth) {
      return parse(with(client -> client.json("GET", "/files/list?root=" + q(text(worktree))
          + "&path=" + q(requestedPath == null ? "." : requestedPath) + "&maxDepth=" + maxDepth
          + limitQuery(), null)), ListFilesResult.class);
    }

    @Override
    public ReadFileResult read(Path worktree, String requestedPath) {
      return parse(with(client -> client.json("GET", "/files/read?root=" + q(text(worktree))
          + "&path=" + q(requestedPath) + limitQuery(), null)), ReadFileResult.class);
    }

    @Override
    public WorkspaceEntryMutation metadata(Path worktree, String requestedPath) {
      return parse(with(client -> client.json("GET", "/files/metadata?root=" + q(text(worktree))
          + "&path=" + q(requestedPath), null)), WorkspaceEntryMutation.class);
    }

    @Override
    public SearchFilesResult search(Path worktree, String requestedPath, String query, int max) {
      return parse(with(client -> client.json("GET", "/files/search?root=" + q(text(worktree))
          + "&path=" + q(requestedPath == null ? "." : requestedPath) + "&query=" + q(query)
          + "&maxResults=" + max + limitQuery(), null)), SearchFilesResult.class);
    }

    private byte[] replacementBytes(String content) {
      if (content == null) throw new IllegalArgumentException("replacementContent is required");
      byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
      if (bytes.length > limits.maxToolFileBytes()) {
        throw new IllegalArgumentException("Replacement exceeds tool size limit");
      }
      return bytes;
    }

    private void requireSha(String value) {
      if (value == null || !SHA256.matcher(value).matches()) {
        throw new IllegalArgumentException("expectedSha256 must be a lowercase SHA-256 hash");
      }
    }

    @Override
    public ReplacementPreview previewReplacement(
        Path worktree, String requestedPath, String expectedSha256, String content) {
      requireSha(expectedSha256);
      byte[] replacement = replacementBytes(content);
      ReadFileResult current = read(worktree, requestedPath);
      if (!current.sha256().equals(expectedSha256)) {
        throw new FileConflictException("File changed since it was read");
      }
      String diff = WorkspaceFileService.unifiedReplacementDiff(
          current.path(), current.content(), content, limits.maxToolOutputBytes());
      return new ReplacementPreview(
          current.path(), expectedSha256, WorkspaceFileService.sha256(replacement), diff);
    }

    @Override
    public ReplacementPreview previewCreation(Path worktree, String requestedPath, String content) {
      byte[] replacement = replacementBytes(content);
      String path = creationPath(worktree, requestedPath);
      return new ReplacementPreview(path, null, WorkspaceFileService.sha256(replacement),
          WorkspaceFileService.unifiedCreationDiff(path, content, limits.maxToolOutputBytes()));
    }

    /** Applies the checks of a new path that need no file content, and normalizes it. */
    private String creationPath(Path worktree, String requestedPath) {
      if (requestedPath == null || requestedPath.indexOf('\0') >= 0) {
        throw new IllegalArgumentException("Workspace path is invalid");
      }
      Path relative = Path.of(requestedPath);
      if (relative.isAbsolute()) throw new IllegalArgumentException("Workspace path must be relative");
      Path normalized = relative.normalize();
      if (normalized.startsWith("..") || normalized.toString().isEmpty()) {
        throw new IllegalArgumentException("Workspace path escapes worktree");
      }
      for (Path segment : normalized) {
        if (".git".equals(segment.toString())) {
          throw new IllegalArgumentException("Git metadata is not writable");
        }
      }
      try {
        Path target = worktree.resolve(normalized);
        Path parent = target.getParent();
        if (parent != null && !parent.equals(worktree)
            && !layout.stat(parent).isDirectory()) {
          throw new IllegalArgumentException("Workspace path parent does not exist");
        }
        if (layout.stat(target).exists()) {
          throw new IllegalArgumentException("File already exists; use create_patch to replace it");
        }
      } catch (IOException exception) {
        throw new IllegalStateException("Could not inspect the workspace", exception);
      }
      return text(normalized);
    }

    @Override
    public ReadFileResult applyReplacement(
        Path worktree, String requestedPath, String expectedSha256, String content) {
      requireSha(expectedSha256);
      replacementBytes(content);
      Map<String, Object> body = body(worktree);
      body.put("path", requestedPath);
      body.put("content", content);
      body.put("expectedSha256", expectedSha256);
      return parse(with(client -> client.json("POST", "/files/write", body)), ReadFileResult.class);
    }

    @Override
    public ReadFileResult applyCreation(Path worktree, String requestedPath, String content) {
      replacementBytes(content);
      String path = creationPath(worktree, requestedPath);
      Map<String, Object> body = body(worktree);
      body.put("path", path);
      body.put("content", content);
      body.put("expectedSha256", null);
      body.put("createParents", false);
      return parse(with(client -> client.json("POST", "/files/write", body)), ReadFileResult.class);
    }

    @Override
    public WorkspaceEntryMutation createDirectory(Path worktree, String requestedPath) {
      return parse(with(client -> client.json("POST", "/files/mkdir",
          Map.of("root", text(worktree), "path", requestedPath))), WorkspaceEntryMutation.class);
    }

    @Override
    public WorkspaceEntryMutation move(
        Path worktree, String sourcePath, String targetPath, String expectedSha256) {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("root", text(worktree));
      body.put("source", sourcePath);
      body.put("target", targetPath);
      body.put("expectedSha256", expectedSha256 == null ? "" : expectedSha256);
      return parse(with(client -> client.json("POST", "/files/move", body)), WorkspaceEntryMutation.class);
    }

    @Override
    public void delete(Path worktree, String requestedPath, String expectedSha256, boolean recursive) {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("root", text(worktree));
      body.put("path", requestedPath);
      body.put("expectedSha256", expectedSha256 == null ? "" : expectedSha256);
      body.put("recursive", recursive);
      with(client -> client.json("POST", "/files/delete", body));
    }

    @Override
    public RawFile readRaw(Path worktree, String requestedPath, long maxBytes) {
      return with(client -> {
        try (AgentResponse response = client.open("GET", "/files/raw?root=" + q(text(worktree))
            + "&path=" + q(requestedPath) + "&maxBytes=" + maxBytes, null)) {
          return new RawFile(response.body().readAllBytes(), false);
        } catch (IllegalArgumentException exception) {
          String message = exception.getMessage() == null ? "" : exception.getMessage();
          if (message.contains("too large")) return RawFile.oversized();
          return RawFile.absent();
        }
      });
    }

    @Override
    public boolean copyTree(Path source, Path destination, Set<String> skip, long maxBytes, int maxFiles) {
      Map<String, Object> body = new LinkedHashMap<>();
      body.put("source", text(source));
      body.put("destination", text(destination));
      body.put("skip", List.copyOf(skip));
      body.put("maxBytes", maxBytes);
      body.put("maxFiles", maxFiles);
      return with(client -> client.json("POST", "/copy", body)).path("copied").asBoolean(false);
    }
  }

  // ---- processes ---------------------------------------------------------------------------

  private final class Processes implements WorkspaceProcesses {
    @Override
    public TestCommandResult exec(
        Path cwd, List<String> argv, Map<String, String> environment, Duration timeout,
        int outputLimit, String digest, Consumer<String> progress) throws IOException {
      return withIo(client -> {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cwd", text(cwd));
        body.put("argv", argv);
        body.put("env", environment);
        body.put("timeoutMillis", timeout.toMillis());
        body.put("outputLimitBytes", outputLimit);
        body.put("inheritPath", true);
        // The agent only adds the directory when the bundle is installed.
        String bundled = text(root.resolve("toolchain").resolve("bin"));
        switch (client.pod().toolchain()) {
          case FALLBACK -> body.put("pathAppend", List.of(bundled));
          case PREFER -> body.put("pathPrepend", List.of(bundled));
          case OFF -> { }
        }
        return stream(client, body, outputLimit, digest, progress);
      });
    }

    private TestCommandResult stream(AgentClient client, Map<String, Object> body, int limit,
        String digest, Consumer<String> progress) throws IOException {
      ByteArrayOutputStream output = new ByteArrayOutputStream();
      long[] lastLine = {System.nanoTime()};
      boolean[] finished = {false};
      try (AgentResponse response = client.open("POST", "/exec", body)) {
        Thread watchdog = Thread.startVirtualThread(() -> {
          try {
            while (!finished[0]) {
              Thread.sleep(5_000);
              if (System.nanoTime() - lastLine[0] > IDLE_STREAM.toNanos()) {
                response.body().close();
                return;
              }
            }
          } catch (InterruptedException | IOException ignored) {
            // the stream ended by itself
          }
        });
        long lastReport = System.nanoTime();
        try (BufferedReader reader = new BufferedReader(
            new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
          String line;
          while ((line = reader.readLine()) != null) {
            lastLine[0] = System.nanoTime();
            JsonNode event = AgentClient.JSON.readTree(line);
            switch (event.path("type").asString("")) {
              case "output" -> {
                byte[] chunk = Base64.getDecoder().decode(event.path("data").asString(""));
                output.write(chunk, 0, Math.min(chunk.length, Math.max(0, limit - output.size())));
                if (progress != null && System.nanoTime() - lastReport > 800_000_000L) {
                  lastReport = System.nanoTime();
                  String partial = output.toString(StandardCharsets.UTF_8);
                  try {
                    progress.accept(partial.length() > 30_000
                        ? "…" + partial.substring(partial.length() - 30_000) : partial);
                  } catch (RuntimeException ignored) {
                    // live progress is best effort
                  }
                }
              }
              case "exit" -> {
                return new TestCommandResult(event.path("exitCode").asInt(-1),
                    output.toString(StandardCharsets.UTF_8), event.path("timedOut").asBoolean(false),
                    event.path("truncated").asBoolean(false), digest);
              }
              default -> { }
            }
          }
        } finally {
          finished[0] = true;
          watchdog.interrupt();
        }
      }
      throw new IOException("The workspace pod stopped answering while the command ran");
    }

    @Override
    public boolean hasTool(String name) {
      if (name == null || !TOOL.matcher(name).matches()) return false;
      return tools.computeIfAbsent(name, key -> {
        try {
          Path cwd = layout.temporaryDirectory();
          return exec(cwd, List.of("sh", "-c", "command -v " + key), Map.of(), Duration.ofSeconds(10),
              4096, null, null).exitCode() == 0;
        } catch (IOException | RuntimeException exception) {
          return false;
        }
      });
    }
  }

  // ---- git ---------------------------------------------------------------------------------

  private final class Git implements WorkspaceGit {
    private Map<String, String> baseEnvironment() {
      Map<String, String> env = new LinkedHashMap<>();
      env.put("GIT_TERMINAL_PROMPT", "0");
      env.put("GIT_CONFIG_NOSYSTEM", "1");
      env.put("GIT_CONFIG_GLOBAL", "/dev/null");
      env.put("LC_ALL", "C");
      // The volume may belong to another user than the one the image runs as.
      env.put("GIT_CONFIG_COUNT", "1");
      env.put("GIT_CONFIG_KEY_0", "safe.directory");
      env.put("GIT_CONFIG_VALUE_0", "*");
      if (ignoreTls) env.put("GIT_SSL_NO_VERIFY", "true");
      return env;
    }

    @Override
    public GitCommandResult run(Path cwd, List<String> arguments) throws IOException {
      return run(cwd, arguments, Map.of(), List.of());
    }

    @Override
    public GitCommandResult run(Path cwd, List<String> arguments, Map<String, String> environment,
        Collection<String> secrets) throws IOException {
      GitCommandRunner.requireSafeArguments(arguments, secrets);
      List<String> argv = new java.util.ArrayList<>(arguments.size() + 1);
      argv.add("git");
      argv.addAll(arguments);
      Map<String, String> env = baseEnvironment();
      env.putAll(environment);
      TestCommandResult result = processes.exec(cwd, argv, env, limits.gitCommandTimeout(),
          limits.maxCommandOutputBytes(), null, null);
      return new GitCommandResult(result.exitCode(),
          GitCommandRunner.redact(result.output(), secrets), result.timedOut(), result.truncated());
    }

    @Override
    public GitCommandResult runWithCredential(
        Path cwd, List<String> arguments, GitCredentialMaterial credential) throws IOException {
      String username = credential.username();
      if (username == null && credential.authenticationType() == GitAuthenticationType.TOKEN) {
        username = "git";
      }
      Map<String, String> env = new LinkedHashMap<>();
      env.put("GIT_ASKPASS", WorkspacePodSpecs.ASKPASS_PATH);
      env.put("GIT_ASKPASS_REQUIRE", "force");
      env.put("PETRI_GIT_USERNAME", username == null ? "" : username);
      env.put("PETRI_GIT_SECRET", credential.secret());
      return run(cwd, arguments, env, List.of(credential.secret()));
    }
  }

  // ---- archives ----------------------------------------------------------------------------

  private final class Archives implements WorkspaceArchives {
    @Override
    public boolean downloadTree(Path hostDirectory, Path localDestination, Set<String> skip,
        long maxBytes, int maxFiles) throws IOException {
      return withIo(client -> {
        try (AgentResponse response = client.open("GET", "/archive?path=" + q(text(hostDirectory))
            + "&skip=" + q(String.join(",", skip)) + "&maxBytes=" + maxBytes + "&maxFiles=" + maxFiles,
            null)) {
          Files.createDirectories(localDestination);
          return Tar.extract(response.body(), localDestination, maxBytes, maxFiles);
        } catch (IllegalArgumentException exception) {
          if (exception.getMessage() != null && exception.getMessage().contains("too large")) return false;
          throw exception;
        }
      });
    }

    @Override
    public void uploadTree(Path localDirectory, Path hostDestination) throws IOException {
      byte[] tar = Tar.pack(localDirectory);
      withIo(client -> {
        try (AgentResponse response = client.openBytes("POST",
            "/upload?path=" + q(text(hostDestination)), tar, "application/x-tar")) {
          response.body().readAllBytes();
          return null;
        }
      });
    }
  }
}
