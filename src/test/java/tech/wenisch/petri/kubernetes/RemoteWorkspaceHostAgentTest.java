package tech.wenisch.petri.kubernetes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Secret;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.wenisch.petri.agent.run.TestCommandResult;
import tech.wenisch.petri.agent.tool.FileConflictException;
import tech.wenisch.petri.config.PetriProperties;
import tech.wenisch.petri.config.TlsSettings;
import tech.wenisch.petri.workspace.HostStat;
import tech.wenisch.petri.workspace.WorkspaceHostKind;

/**
 * Drives the remote workspace host against the real Go agent binary (built by
 * scripts/build-workspace-agent.sh). Skipped when the binary is not there, so the contract between
 * the Java client and the agent is checked wherever both are available.
 */
class RemoteWorkspaceHostAgentTest {
  private static final Path BINARY = Path.of("data/workspace-agent/amd64/petri-workspace-agent");
  private static final String TOKEN = "test-token";

  @TempDir Path workspace;
  private Process agent;
  private RemoteWorkspaceHost host;
  private final UUID projectId = UUID.randomUUID();
  private Path root;
  private LocalAgentGateway gateway;
  private volatile ToolchainMode toolchain = ToolchainMode.OFF;

  /** Forwards agent calls to a locally running agent instead of a pod. */
  private static final class LocalAgentGateway implements ClusterGateway {
    private final HttpClient http = HttpClient.newHttpClient();
    private final int port;
    /** Answers the next calls like the API server does for a pod that does not exist. */
    private volatile int podMissing;

    LocalAgentGateway(int port) {
      this.port = port;
    }

    @Override
    public AgentResponse call(ClusterConfig cluster, String namespace, String pod, int ignored,
        String method, String path, Map<String, String> headers, byte[] body, String contentType)
        throws IOException {
      if (podMissing > 0) {
        podMissing--;
        byte[] status = "{\"kind\":\"Status\",\"message\":\"pods \\\"pod\\\" not found\"}"
            .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        return new AgentResponse(404, new java.io.ByteArrayInputStream(status));
      }
      var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
          .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
              : HttpRequest.BodyPublishers.ofByteArray(body));
      headers.forEach(request::header);
      if (contentType != null) request.header("Content-Type", contentType);
      try {
        HttpResponse<java.io.InputStream> response =
            http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        return new AgentResponse(response.statusCode(), response.body());
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IOException(exception);
      }
    }

    @Override public Check check(ClusterConfig c, String n) { throw new UnsupportedOperationException(); }
    @Override public Optional<PodInfo> pod(ClusterConfig c, String n, String p) { throw new UnsupportedOperationException(); }
    @Override public void applySecret(ClusterConfig c, Secret s) { throw new UnsupportedOperationException(); }
    @Override public void createPvcIfAbsent(ClusterConfig c, PersistentVolumeClaim p) { throw new UnsupportedOperationException(); }
    @Override public void createPod(ClusterConfig c, Pod p) { throw new UnsupportedOperationException(); }
    @Override public void deletePod(ClusterConfig c, String n, String p) { throw new UnsupportedOperationException(); }
    @Override public void deletePvc(ClusterConfig c, String n, String p) { throw new UnsupportedOperationException(); }
    @Override public void deleteSecret(ClusterConfig c, String n, String p) { throw new UnsupportedOperationException(); }
    @Override public String exec(ClusterConfig c, String n, String p, String ct, Duration t, String... cmd) { throw new UnsupportedOperationException(); }
    @Override public void install(ClusterConfig c, String n, String p, String ct, byte[] d, String s, Duration t) { throw new UnsupportedOperationException(); }
  }

  @BeforeEach
  void startAgent() throws Exception {
    assumeTrue(Files.isExecutable(BINARY), "workspace agent binary not built");
    assumeTrue(System.getProperty("os.name").toLowerCase().contains("linux"));
    int port;
    try (ServerSocket socket = new ServerSocket(0)) {
      port = socket.getLocalPort();
    }
    root = workspace.toRealPath().resolve("petri");
    ProcessBuilder builder = new ProcessBuilder(BINARY.toAbsolutePath().toString(), "serve");
    builder.environment().put("PETRI_AGENT_TOKEN", TOKEN);
    builder.environment().put("PETRI_AGENT_LISTEN", "127.0.0.1:" + port);
    builder.environment().put("PETRI_WORKSPACE_ROOT", root.toString());
    builder.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
    agent = builder.start();
    var gateway = new LocalAgentGateway(port);
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (System.nanoTime() < deadline) {
      try (var response = gateway.call(null, "ns", "pod", 0, "GET", "/v1/healthz", Map.of(), null, null)) {
        if (response.status() == 200) break;
      } catch (IOException notYet) {
        Thread.sleep(100);
      }
    }
    PodSessions sessions = id -> new PodSessions.Session() {
      @Override public PodHandle handle() {
        return new PodHandle(id, new ClusterConfig(ClusterConfig.AuthMode.IN_CLUSTER, null, null, null, null, false),
            "ns", "pod", TOKEN, toolchain);
      }
      @Override public void close() { }
    };
    PetriProperties properties = new PetriProperties(null, null, new PetriProperties.Workspace(
        root.toString(), Duration.ofSeconds(30), 200_000, 100_000, 100_000, 50, null, null), null);
    this.gateway = gateway;
    host = new RemoteWorkspaceHost(projectId, sessions, gateway, properties, TlsSettings.verifying(), root);
  }

  @AfterEach
  void stopAgent() {
    if (agent != null) agent.destroyForcibly();
  }

  @Test
  void theLayoutUsesTheSamePathsAsALocalWorkspace() throws Exception {
    var layout = host.layout();
    Path worktreeId = layout.runWorktree(projectId, UUID.randomUUID());
    assertThat(host.kind()).isEqualTo(WorkspaceHostKind.KUBERNETES);
    assertThat(worktreeId.getParent()).isEqualTo(root.resolve("runs").resolve(projectId.toString()));
    assertThat(layout.mirror(projectId).toString()).endsWith("/mirrors/" + projectId + ".git");
    assertThatThrownBy(() -> layout.requireExistingPath(Path.of("/etc"))).isInstanceOf(IllegalArgumentException.class);
  }

  /** The first mirror sync asks whether the mirror directory is empty; like locally, it exists by then. */
  @Test
  void theMirrorDirectoryExistsAndIsEmptyBeforeTheFirstClone() throws Exception {
    Path mirror = host.layout().mirror(projectId);
    assertThat(host.layout().stat(mirror).exists()).isTrue();
    assertThat(host.layout().isEmptyDirectory(mirror)).isTrue();
  }

  /** A pod deleted behind Petri's back is started again and the request repeated once. */
  @Test
  void aVanishedPodIsStartedAgainAndTheRequestRepeated() throws Exception {
    Path directory = Files.createDirectories(root.resolve("tmp"));
    gateway.podMissing = 1;
    assertThat(host.layout().stat(directory).exists()).isTrue();
    assertThat(gateway.podMissing).isZero();
    // Still failing after the retry is reported, not looped on.
    gateway.podMissing = 2;
    assertThatThrownBy(() -> host.layout().stat(directory))
        .isInstanceOf(WorkspaceHostUnavailableException.class);
  }

  /** The bundle as the image builder makes it: a gzip-compressed tar with a marker and a bin directory. */
  private byte[] toolchainBundle(String version, String tool, String output) throws Exception {
    Path staging = Files.createTempDirectory(workspace, "bundle-");
    Files.createDirectories(staging.resolve("bin"));
    Path script = staging.resolve("bin").resolve(tool);
    Files.writeString(script, "#!/bin/sh\necho " + output + "\n");
    assertThat(script.toFile().setExecutable(true)).isTrue();
    Files.writeString(staging.resolve(".petri-toolchain.json"), "{\"version\":\"" + version
        + "\",\"prefix\":\"" + root.resolve("toolchain") + "\",\"arch\":\"amd64\"}");
    var bytes = new java.io.ByteArrayOutputStream();
    try (var gzip = new java.util.zip.GZIPOutputStream(bytes)) {
      gzip.write(Tar.pack(staging));
    }
    return bytes.toByteArray();
  }

  @Test
  void theBundledToolsAreCopiedIntoThePodAndFoundOnThePath() throws Exception {
    var client = new AgentClient(gateway, new PodHandle(projectId, null, "ns", "pod", TOKEN));
    byte[] bundle = toolchainBundle("v1", "petri-hello", "from-the-bundle");
    try (var response = client.openStream("POST", "/toolchain",
        new java.io.ByteArrayInputStream(bundle), bundle.length, "application/gzip")) {
      assertThat(response.status()).isEqualTo(200);
    }
    assertThat(client.json("GET", "/toolchain", null).path("version").asString()).isEqualTo("v1");
    assertThat(client.json("GET", "/info", null).path("toolchain").path("tools").get(0).asString())
        .isEqualTo("petri-hello");

    Path cwd = worktree();
    assertThatThrownBy(() -> host.processes().exec(cwd, List.of("petri-hello"), Map.of(),
        Duration.ofSeconds(10), 1000, null, null))
        .as("off: the bundle is not on the PATH").hasMessageContaining("not found");

    toolchain = ToolchainMode.FALLBACK;
    var result = host.processes().exec(cwd, List.of("petri-hello"), Map.of(), Duration.ofSeconds(10), 1000,
        null, null);
    assertThat(result.exitCode()).isZero();
    assertThat(result.output()).contains("from-the-bundle");
    assertThat(host.processes().hasTool("petri-hello")).isTrue();
  }

  @Test
  void preferPutsTheBundleBeforeTheImageAndFallbackAfterIt() throws Exception {
    var client = new AgentClient(gateway, new PodHandle(projectId, null, "ns", "pod", TOKEN));
    byte[] bundle = toolchainBundle("v1", "echo", "bundled-echo");
    try (var response = client.openStream("POST", "/toolchain",
        new java.io.ByteArrayInputStream(bundle), bundle.length, "application/gzip")) {
      assertThat(response.status()).isEqualTo(200);
    }
    Path cwd = worktree();
    toolchain = ToolchainMode.FALLBACK;
    assertThat(host.processes().exec(cwd, List.of("echo", "image"), Map.of(), Duration.ofSeconds(10), 1000,
        null, null).output()).as("the image's own echo wins").contains("image").doesNotContain("bundled");
    toolchain = ToolchainMode.PREFER;
    assertThat(host.processes().exec(cwd, List.of("echo", "image"), Map.of(), Duration.ofSeconds(10), 1000,
        null, null).output()).as("the bundle wins").contains("bundled-echo");
  }

  @Test
  void aTreeIsCopiedInsideThePodAndOnlyIntoScratchSpace() throws Exception {
    Path source = worktree();
    Files.writeString(source.resolve("a.txt"), "one");
    Files.createDirectories(source.resolve("node_modules"));
    Files.writeString(source.resolve("node_modules/skip.js"), "x");
    Path scratch = host.layout().createTemporaryDirectory("copy-test-");

    assertThat(host.files().copyTree(source, scratch.resolve("project"), Set.of("node_modules"), 1000, 10)).isTrue();
    assertThat(Files.readString(scratch.resolve("project/a.txt"))).isEqualTo("one");
    assertThat(scratch.resolve("project/node_modules")).doesNotExist();
    assertThat(host.files().copyTree(source, scratch.resolve("small"), Set.of(), 1, 10)).isFalse();
    assertThatThrownBy(() -> host.files().copyTree(source, root.resolve("runs/elsewhere"), Set.of(), 1000, 10))
        .isInstanceOf(IllegalArgumentException.class);
  }

  /** A worktree inside the temporary agent root; the agent's configured root differs from /petri. */
  private Path worktree() throws IOException {
    Path worktree = Files.createDirectories(root.resolve("runs").resolve(projectId.toString()).resolve(UUID.randomUUID().toString()));
    return worktree;
  }

  @Test
  void filesAreEditedWithHashGuardsLikeInAWorkspaceOnDisk() throws Exception {
    Path worktree = worktree();
    var files = host.files();
    files.createDirectory(worktree, "src");
    var created = files.applyCreation(worktree, "src/readme.txt", "hello");
    assertThat(created.content()).isEqualTo("hello");
    var preview = files.previewReplacement(worktree, "src/readme.txt", created.sha256(), "hello world");
    assertThat(preview.diff()).contains("-hello").contains("+hello world");
    var saved = files.applyReplacement(worktree, "src/readme.txt", created.sha256(), "hello world");
    assertThat(saved.content()).isEqualTo("hello world");
    assertThatThrownBy(() -> files.applyReplacement(worktree, "src/readme.txt", created.sha256(), "x"))
        .isInstanceOf(FileConflictException.class);
    assertThatThrownBy(() -> files.read(worktree, "../escape")).isInstanceOf(IllegalArgumentException.class);
    assertThat(files.list(worktree, ".", 3).entries()).extracting(e -> e.path())
        .containsExactly("src", "src/readme.txt");
    assertThat(files.search(worktree, ".", "world", 10).matches()).hasSize(1);
    assertThat(files.readRaw(worktree, "src/readme.txt", 100).bytes()).isEqualTo("hello world".getBytes());
    assertThat(files.readRaw(worktree, "src/readme.txt", 3).tooLarge()).isTrue();
    assertThat(files.readRaw(worktree, "missing", 100).present()).isFalse();
    var moved = files.move(worktree, "src/readme.txt", "src/moved.txt", saved.sha256());
    assertThat(moved.path()).isEqualTo("src/moved.txt");
    files.delete(worktree, "src", null, true);
    assertThat(files.list(worktree, ".", 1).entries()).isEmpty();
  }

  @Test
  void creatingAFileNeedsAnExistingParentAndAnUnusedName() throws Exception {
    Path worktree = worktree();
    assertThatThrownBy(() -> host.files().applyCreation(worktree, "missing/dir/f.txt", "x"))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("parent");
    host.files().applyCreation(worktree, "f.txt", "x");
    assertThatThrownBy(() -> host.files().previewCreation(worktree, "f.txt", "y"))
        .hasMessageContaining("already exists");
    assertThatThrownBy(() -> host.files().previewCreation(worktree, ".git/config", "y"))
        .hasMessageContaining("Git metadata");
  }

  @Test
  void commandsStreamOutputAndReportTheExitAndTheTimeout() throws Exception {
    Path worktree = worktree();
    List<String> progress = new ArrayList<>();
    TestCommandResult result = host.processes().exec(worktree, List.of("sh", "-c", "echo out; echo err >&2; exit 3"),
        Map.of("FOO", "bar"), Duration.ofSeconds(10), 10_000, "digest", progress::add);
    assertThat(result.exitCode()).isEqualTo(3);
    assertThat(result.output()).contains("out").contains("err");
    assertThat(result.imageDigest()).isEqualTo("digest");

    TestCommandResult env = host.processes().exec(worktree, List.of("sh", "-c", "echo $FOO"),
        Map.of("FOO", "bar"), Duration.ofSeconds(10), 10_000, null, null);
    assertThat(env.output().strip()).isEqualTo("bar");

    TestCommandResult timeout = host.processes().exec(worktree, List.of("sh", "-c", "sleep 20"),
        Map.of(), Duration.ofMillis(400), 10_000, null, null);
    assertThat(timeout.timedOut()).isTrue();
    assertThat(timeout.exitCode()).isEqualTo(-1);

    TestCommandResult capped = host.processes().exec(worktree, List.of("sh", "-c", "yes x | head -c 5000"),
        Map.of(), Duration.ofSeconds(10), 100, null, null);
    assertThat(capped.truncated()).isTrue();
    assertThat(capped.output()).hasSize(100);

    assertThat(host.processes().hasTool("sh")).isTrue();
    assertThat(host.processes().hasTool("definitely-not-installed-tool")).isFalse();
    assertThat(host.processes().hasTool("bad name; rm")).isFalse();
  }

  @Test
  void gitRunsInThePodWithItsOwnEnvironmentAndRedactsSecrets() throws Exception {
    assumeTrue(Files.isExecutable(Path.of("/usr/bin/git")) || Files.isExecutable(Path.of("/bin/git")), "git missing");
    Path worktree = worktree();
    var git = host.git();
    assertThat(git.run(worktree, List.of("init", "-q")).succeeded()).isTrue();
    var status = git.run(worktree, List.of("status", "--porcelain"));
    assertThat(status.succeeded()).isTrue();
    var failure = git.run(worktree, List.of("ls-remote", "https://user:s3cr3t@127.0.0.1:1/repo.git"),
        Map.of(), List.of());
    assertThat(failure.succeeded()).isFalse();
    assertThat(failure.output()).doesNotContain("s3cr3t");
    assertThatThrownBy(() -> git.run(worktree, List.of("log", "s3cr3t"), Map.of(), List.of("s3cr3t")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void temporaryDirectoriesAndStatsWorkAndTreesRoundTripThroughTar() throws Exception {
    var layout = host.layout();
    Path temp = layout.createTemporaryDirectory("petri-test-");
    assertThat(temp.toString()).contains("/tmp/petri-test-");
    assertThat(layout.stat(temp).isDirectory()).isTrue();
    assertThat(layout.isEmptyDirectory(temp)).isTrue();
    layout.deleteTemporary(temp);
    assertThat(layout.stat(temp)).isEqualTo(HostStat.missing());

    Path local = Files.createDirectories(workspace.resolve("local-src"));
    Files.createDirectories(local.resolve("conf/nested"));
    Files.writeString(local.resolve("conf/nested/" + "n".repeat(120) + ".txt"), "long name");
    Files.writeString(local.resolve("settings.xml"), "<settings/>");
    Path hostDir = layout.createTemporaryDirectory("petri-pkg-");
    host.archives().uploadTree(local, hostDir);
    assertThat(layout.stat(hostDir.resolve("settings.xml")).isFile()).isTrue();

    Path back = workspace.resolve("downloaded");
    assertThat(host.archives().downloadTree(hostDir, back, Set.of("nothing"), 1_000_000, 100)).isTrue();
    assertThat(Files.readString(back.resolve("settings.xml"))).isEqualTo("<settings/>");
    assertThat(Files.readString(back.resolve("conf/nested/" + "n".repeat(120) + ".txt"))).isEqualTo("long name");
    assertThat(host.archives().downloadTree(hostDir, workspace.resolve("small"), Set.of(), 3, 100)).isFalse();
  }

  @Test
  void aWrongTokenIsReportedAsAnUnavailableWorkspace() throws Exception {
    PodSessions wrong = id -> new PodSessions.Session() {
      @Override public PodHandle handle() {
        return new PodHandle(id, null, "ns", "pod", "wrong");
      }
      @Override public void close() { }
    };
    var other = new RemoteWorkspaceHost(projectId, wrong, gateway, new PetriProperties(
        null, null, new PetriProperties.Workspace(root.toString(), Duration.ofSeconds(5), 200_000, 100_000, 100_000, 50, null, null), null),
        TlsSettings.verifying(), root);
    assertThatThrownBy(() -> other.layout().stat(root))
        .hasMessageContaining("rejected");
  }
}
