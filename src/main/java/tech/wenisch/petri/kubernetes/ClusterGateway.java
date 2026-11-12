package tech.wenisch.petri.kubernetes;

import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Secret;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Everything Petri needs from a Kubernetes cluster. The production implementation uses the fabric8
 * client; tests substitute a fake so the orchestration logic needs no cluster.
 */
public interface ClusterGateway {
  /** Result of testing a connection. */
  record Check(boolean ok, String message) {}

  /** What the cluster says about a pod, reduced to what Petri decides on. */
  record PodInfo(
      String phase, boolean ready, String nodeName, String image, String imageId,
      String waitingReason, String waitingMessage, boolean deleting) {
    public boolean running() {
      return "Running".equals(phase) && !deleting;
    }
  }

  /** The streamed answer of the agent. The body must be closed. */
  record AgentResponse(int status, InputStream body) implements AutoCloseable {
    @Override
    public void close() throws IOException {
      body.close();
    }
  }

  /** Verifies the credentials, the namespace and the permissions Petri needs. */
  Check check(ClusterConfig cluster, String namespace);

  Optional<PodInfo> pod(ClusterConfig cluster, String namespace, String name);

  /** Creates the secret or replaces its content. */
  void applySecret(ClusterConfig cluster, Secret secret);

  void createPvcIfAbsent(ClusterConfig cluster, PersistentVolumeClaim claim);

  void createPod(ClusterConfig cluster, Pod pod);

  void deletePod(ClusterConfig cluster, String namespace, String name);

  void deletePvc(ClusterConfig cluster, String namespace, String name);

  void deleteSecret(ClusterConfig cluster, String namespace, String name);

  /** Runs a command in the pod and returns its standard output; a non-zero exit fails. */
  String exec(ClusterConfig cluster, String namespace, String pod, String container,
      Duration timeout, String... command) throws IOException;

  /** Streams {@code data} to the standard input of {@code sh -c script}. */
  void install(ClusterConfig cluster, String namespace, String pod, String container, byte[] data,
      String script, Duration timeout) throws IOException;

  /** Calls the agent through the API server's pod proxy. */
  AgentResponse call(ClusterConfig cluster, String namespace, String pod, int port, String method,
      String path, Map<String, String> headers, byte[] body, String contentType) throws IOException;

  /**
   * As above with a body that is streamed instead of held in memory, for the toolchain bundle. The
   * default reads it completely; the production implementation does not.
   */
  default AgentResponse call(ClusterConfig cluster, String namespace, String pod, int port,
      String method, String path, Map<String, String> headers, InputStream body, long length,
      String contentType) throws IOException {
    return call(cluster, namespace, pod, port, method, path, headers, body.readAllBytes(), contentType);
  }
}
