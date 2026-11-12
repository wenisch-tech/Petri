package tech.wenisch.petri.kubernetes;

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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import tech.wenisch.petri.config.PetriProperties;
import tech.wenisch.petri.config.TlsSettings;
import tech.wenisch.petri.workspace.WorkspaceHost;

/**
 * The real Go workspace agent (built by scripts/build-workspace-agent.sh) running on this machine
 * with a temporary workspace root, reached through a remote workspace host exactly as a pod would be.
 * Tests that use it are skipped when the binary has not been built.
 */
public final class RealAgent implements AutoCloseable {
  private static final Path BINARY = Path.of("data/workspace-agent/amd64/petri-workspace-agent");
  private static final String TOKEN = "test-token";

  private final Process process;
  private final Path root;
  private final int port;

  private RealAgent(Process process, Path root, int port) {
    this.process = process;
    this.root = root;
    this.port = port;
  }

  /** Starts the agent below {@code workspace}, or skips the calling test. */
  public static RealAgent start(Path workspace) throws Exception {
    return start(workspace, null);
  }

  /** As above, with {@code tools} in front of the agent's PATH, so tests can supply stub commands. */
  public static RealAgent start(Path workspace, Path tools) throws Exception {
    assumeTrue(Files.isExecutable(BINARY), "workspace agent binary not built");
    assumeTrue(System.getProperty("os.name").toLowerCase().contains("linux"));
    int port;
    try (ServerSocket socket = new ServerSocket(0)) {
      port = socket.getLocalPort();
    }
    Path root = workspace.toRealPath().resolve("petri");
    ProcessBuilder builder = new ProcessBuilder(BINARY.toAbsolutePath().toString(), "serve");
    if (tools != null) {
      builder.environment().put("PATH", tools + ":" + System.getenv().getOrDefault("PATH", "/usr/bin:/bin"));
    }
    builder.environment().put("PETRI_AGENT_TOKEN", TOKEN);
    builder.environment().put("PETRI_AGENT_LISTEN", "127.0.0.1:" + port);
    builder.environment().put("PETRI_WORKSPACE_ROOT", root.toString());
    builder.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
    RealAgent agent = new RealAgent(builder.start(), root, port);
    long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
    while (System.nanoTime() < deadline) {
      try (var response = new Gateway(port).call(null, "ns", "pod", 0, "GET", "/v1/healthz", Map.of(), null, null)) {
        if (response.status() == 200) break;
      } catch (IOException notYet) {
        Thread.sleep(100);
      }
    }
    return agent;
  }

  public Path root() {
    return root;
  }

  /** A host for one project whose pod is this agent. */
  public WorkspaceHost host(UUID projectId) {
    PodSessions sessions = id -> new PodSessions.Session() {
      @Override public PodHandle handle() {
        return new PodHandle(id, new ClusterConfig(ClusterConfig.AuthMode.IN_CLUSTER, null, null, null, null, false),
            "ns", "pod", TOKEN, ToolchainMode.OFF);
      }
      @Override public void close() { }
    };
    PetriProperties properties = new PetriProperties(null, null, new PetriProperties.Workspace(
        root.toString(), Duration.ofSeconds(30), 200_000, 100_000, 100_000, 50, null, null), null);
    return new RemoteWorkspaceHost(projectId, sessions, new Gateway(port), properties,
        TlsSettings.verifying(), root);
  }

  @Override
  public void close() {
    process.destroyForcibly();
  }

  /** Forwards agent calls to the local agent instead of a pod. */
  private static final class Gateway implements ClusterGateway {
    private final HttpClient http = HttpClient.newHttpClient();
    private final int port;

    Gateway(int port) {
      this.port = port;
    }

    @Override
    public AgentResponse call(ClusterConfig cluster, String namespace, String pod, int ignored,
        String method, String path, Map<String, String> headers, byte[] body, String contentType)
        throws IOException {
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
}
