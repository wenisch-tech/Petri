package tech.wenisch.petri.kubernetes;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tech.wenisch.petri.agent.tool.FileConflictException;
import tech.wenisch.petri.kubernetes.ClusterGateway.AgentResponse;

/** Speaks the workspace agent's HTTP API through the cluster's pod proxy. */
public class AgentClient {
  static final JsonMapper JSON = JsonMapper.builder().build();
  private static final int MAX_JSON = 80 << 20;

  private final ClusterGateway gateway;
  private final PodHandle pod;

  public AgentClient(ClusterGateway gateway, PodHandle pod) {
    this.gateway = gateway;
    this.pod = pod;
  }

  public PodHandle pod() {
    return pod;
  }

  /** Sends a request and returns the open response; errors are already translated. */
  public AgentResponse open(String method, String path, Object body) throws IOException {
    byte[] payload = body == null ? null : JSON.writeValueAsBytes(body);
    return openBytes(method, path, payload, payload == null ? null : "application/json");
  }

  /** Sends a large body from a stream, for example the toolchain bundle. */
  public AgentResponse openStream(String method, String path, InputStream payload, long length,
      String contentType) throws IOException {
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("X-Petri-Agent-Token", pod.agentToken());
    AgentResponse response = gateway.call(pod.cluster(), pod.namespace(), pod.podName(),
        WorkspacePodSpecs.AGENT_PORT, method, "/v1" + path, headers, payload, length, contentType);
    if (response.status() >= 200 && response.status() < 300) return response;
    String text;
    try (response) {
      text = new String(response.body().readNBytes(64 * 1024), StandardCharsets.UTF_8);
    }
    throw translate(response.status(), text);
  }

  /** As {@link #open} with an already encoded body, for example a tar archive. */
  public AgentResponse openBytes(String method, String path, byte[] payload, String contentType)
      throws IOException {
    Map<String, String> headers = new LinkedHashMap<>();
    // Not Authorization: the API server's pod proxy consumes that header and never forwards it.
    headers.put("X-Petri-Agent-Token", pod.agentToken());
    AgentResponse response = gateway.call(pod.cluster(), pod.namespace(), pod.podName(),
        WorkspacePodSpecs.AGENT_PORT, method, "/v1" + path, headers, payload, contentType);
    if (response.status() >= 200 && response.status() < 300) return response;
    String text;
    try (response) {
      text = new String(response.body().readNBytes(64 * 1024), StandardCharsets.UTF_8);
    }
    throw translate(response.status(), text);
  }

  public JsonNode json(String method, String path, Object body) throws IOException {
    try (AgentResponse response = open(method, path, body)) {
      if (response.status() == 204) return JSON.createObjectNode();
      return JSON.readTree(read(response.body()));
    }
  }

  public byte[] bytes(String path) throws IOException {
    try (AgentResponse response = open("GET", path, null)) {
      return read(response.body());
    }
  }

  private static byte[] read(InputStream stream) throws IOException {
    byte[] data = stream.readNBytes(MAX_JSON + 1);
    if (data.length > MAX_JSON) throw new IOException("The workspace agent answered with too much data");
    return data;
  }

  static RuntimeException translate(int status, String body) {
    String kind = null;
    String message = null;
    try {
      JsonNode node = JSON.readTree(body);
      kind = node.path("kind").asString(null);
      message = node.path("error").asString(null);
      if ("Status".equals(kind)) {
        // The API server itself answered, not the agent.
        String detail = node.path("message").asString("");
        if (status == 404 || status == 503) {
          return WorkspaceHostUnavailableException.podGone(
              "The Kubernetes workspace pod is not running. Start it under Project settings > "
                  + "Kubernetes workspace. " + detail);
        }
        return new WorkspaceHostUnavailableException(
            "The Kubernetes API server refused the request (" + status + "): " + detail);
      }
    } catch (RuntimeException ignored) {
      // not JSON: fall through to the generic message
    }
    if (status == 401) {
      return new WorkspaceHostUnavailableException("The workspace agent rejected Petri's token. "
          + "Stop and start the workspace pod to renew it.");
    }
    if (message == null) message = "The workspace agent failed (" + status + ")";
    if (status == 409 || "conflict".equals(kind)) return new FileConflictException(message);
    if (status == 422) return new IllegalStateException(message);
    if (status >= 500) return new IllegalStateException(message);
    return new IllegalArgumentException(message);
  }
}
