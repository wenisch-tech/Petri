package tech.wenisch.petri.kubernetes;

import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Secret;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** An in-memory cluster whose pods start at once and whose agent answers a few calls. */
class FakeClusterGateway implements ClusterGateway {
  final Map<String, Pod> pods = new LinkedHashMap<>();
  final Map<String, PersistentVolumeClaim> claims = new LinkedHashMap<>();
  final Map<String, Secret> secrets = new LinkedHashMap<>();
  final List<String> calls = new ArrayList<>();
  final List<Integer> installedSizes = new ArrayList<>();
  /** The version of the toolchain bundle the pod's volume holds, or null when it holds none. */
  String toolchainVersion;
  /** The version the volume holds after a bundle was uploaded. */
  String versionAfterUpload = "bundle-1";
  final List<Integer> toolchainUploads = new ArrayList<>();
  boolean failToolchainUpload;
  String machine = "x86_64";
  boolean gitAvailable = true;
  String waitingReason;
  boolean podRuns = true;
  int mkdirStatus = 200;

  @Override
  public Check check(ClusterConfig cluster, String namespace) {
    return new Check(true, "fake");
  }

  @Override
  public Optional<PodInfo> pod(ClusterConfig cluster, String namespace, String name) {
    Pod pod = pods.get(namespace + "/" + name);
    if (pod == null) return Optional.empty();
    String image = pod.getSpec().getContainers().get(0).getImage();
    return Optional.of(new PodInfo(podRuns ? "Running" : "Pending", podRuns, "node-1", image,
        "sha256:digest", waitingReason, waitingReason == null ? null : "pull failed", false));
  }

  @Override
  public void applySecret(ClusterConfig cluster, Secret secret) {
    secrets.put(secret.getMetadata().getNamespace() + "/" + secret.getMetadata().getName(), secret);
    calls.add("secret");
  }

  @Override
  public void createPvcIfAbsent(ClusterConfig cluster, PersistentVolumeClaim claim) {
    claims.putIfAbsent(claim.getMetadata().getNamespace() + "/" + claim.getMetadata().getName(), claim);
    calls.add("pvc");
  }

  @Override
  public void createPod(ClusterConfig cluster, Pod pod) {
    pods.put(pod.getMetadata().getNamespace() + "/" + pod.getMetadata().getName(), pod);
    calls.add("pod");
  }

  @Override
  public void deletePod(ClusterConfig cluster, String namespace, String name) {
    pods.remove(namespace + "/" + name);
    calls.add("delete-pod");
  }

  @Override
  public void deletePvc(ClusterConfig cluster, String namespace, String name) {
    claims.remove(namespace + "/" + name);
    calls.add("delete-pvc");
  }

  @Override
  public void deleteSecret(ClusterConfig cluster, String namespace, String name) {
    secrets.remove(namespace + "/" + name);
    calls.add("delete-secret");
  }

  @Override
  public String exec(ClusterConfig cluster, String namespace, String pod, String container,
      Duration timeout, String... command) {
    return machine + "\n";
  }

  @Override
  public void install(ClusterConfig cluster, String namespace, String pod, String container,
      byte[] data, String script, Duration timeout) {
    installedSizes.add(data.length);
    calls.add("install");
  }

  @Override
  public AgentResponse call(ClusterConfig cluster, String namespace, String pod, int port,
      String method, String path, Map<String, String> headers, byte[] body, String contentType)
      throws IOException {
    if (!pods.containsKey(namespace + "/" + pod)) {
      return answer(404, "{\"kind\":\"Status\",\"message\":\"pod not found\"}");
    }
    if (installedSizes.isEmpty()) throw new IOException("agent not installed yet");
    calls.add(method + " " + path);
    if (path.endsWith("/info")) {
      return answer(200, "{\"root\":\"/petri/workspace\",\"uid\":1000,\"git\":{\"available\":"
          + gitAvailable + "},\"sh\":{\"available\":true},\"toolchain\":" + toolchainJson() + "}");
    }
    if (path.endsWith("/toolchain") && "GET".equals(method)) return answer(200, toolchainJson());
    if (path.endsWith("/toolchain") && "POST".equals(method)) {
      if (failToolchainUpload) return answer(400, "{\"error\":\"bundle refused\",\"kind\":\"invalid\"}");
      toolchainUploads.add(body.length);
      toolchainVersion = versionAfterUpload;
      return answer(200, toolchainJson());
    }
    if (path.endsWith("/mkdir")) {
      return mkdirStatus == 200 ? answer(200, "{\"path\":\"x\"}")
          : answer(mkdirStatus, "{\"error\":\"denied\",\"kind\":\"invalid\"}");
    }
    return answer(200, "{}");
  }

  private String toolchainJson() {
    return toolchainVersion == null ? "{\"present\":false,\"version\":\"\",\"tools\":[]}"
        : "{\"present\":true,\"version\":\"" + toolchainVersion + "\",\"tools\":[\"git\",\"python3\"]}";
  }

  private static AgentResponse answer(int status, String body) {
    return new AgentResponse(status, new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
  }
}
