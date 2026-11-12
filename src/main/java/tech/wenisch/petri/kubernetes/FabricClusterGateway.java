package tech.wenisch.petri.kubernetes;

import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodCondition;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.authorization.v1.SelfSubjectAccessReview;
import io.fabric8.kubernetes.api.model.authorization.v1.SelfSubjectAccessReviewBuilder;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import io.fabric8.kubernetes.client.http.HttpRequest;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;

/** The fabric8 based connection to a cluster, with one cached client per distinct credential. */
@Component
class FabricClusterGateway implements ClusterGateway {
  private final ConcurrentHashMap<String, KubernetesClient> clients = new ConcurrentHashMap<>();

  private KubernetesClient client(ClusterConfig cluster) {
    return clients.computeIfAbsent(cluster.cacheKey(), key -> build(cluster));
  }

  private static KubernetesClient build(ClusterConfig cluster) {
    Config config = switch (cluster.authMode()) {
      case KUBECONFIG -> Config.fromKubeconfig(cluster.kubeconfig());
      case TOKEN -> {
        ConfigBuilder builder = new ConfigBuilder(Config.empty())
            .withMasterUrl(cluster.apiServerUrl())
            .withOauthToken(cluster.token())
            .withTrustCerts(cluster.insecure());
        if (cluster.caPem() != null) {
          builder.withCaCertData(Base64.getEncoder()
              .encodeToString(cluster.caPem().getBytes(StandardCharsets.UTF_8)));
        }
        yield builder.build();
      }
      case IN_CLUSTER -> Config.autoConfigure(null);
    };
    config.setRequestTimeout(30_000);
    config.setConnectionTimeout(10_000);
    return new KubernetesClientBuilder().withConfig(config).build();
  }

  @Override
  public Check check(ClusterConfig cluster, String namespace) {
    try {
      KubernetesClient client = client(cluster);
      String version = client.getKubernetesVersion().getGitVersion();
      if (namespace != null) {
        try {
          if (client.namespaces().withName(namespace).get() == null) {
            return new Check(false, "Connected to Kubernetes " + version + ", but the namespace "
                + namespace + " does not exist. Create it first.");
          }
        } catch (KubernetesClientException forbidden) {
          if (forbidden.getCode() != 403) throw forbidden;
        }
        List<String> missing = new ArrayList<>();
        for (String[] need : new String[][] {
            {"pods", "", "create"}, {"pods", "", "delete"}, {"pods", "", "get"},
            {"pods", "exec", "create"}, {"pods", "proxy", "get"}, {"pods", "proxy", "create"},
            {"persistentvolumeclaims", "", "create"}, {"secrets", "", "create"}}) {
          if (!allowed(client, namespace, need[0], need[1], need[2])) {
            missing.add(need[2] + " " + need[0] + (need[1].isEmpty() ? "" : "/" + need[1]));
          }
        }
        if (!missing.isEmpty()) {
          return new Check(false, "Connected to Kubernetes " + version
              + ", but these permissions are missing in " + namespace + ": "
              + String.join(", ", missing));
        }
      }
      return new Check(true, "Connected to Kubernetes " + version
          + (namespace == null ? "." : ". Namespace " + namespace + " is usable."));
    } catch (RuntimeException exception) {
      return new Check(false, "Could not connect: " + brief(exception));
    }
  }

  private static boolean allowed(
      KubernetesClient client, String namespace, String resource, String subresource, String verb) {
    try {
      SelfSubjectAccessReview review = new SelfSubjectAccessReviewBuilder()
          .withNewSpec().withNewResourceAttributes()
              .withNamespace(namespace).withVerb(verb).withResource(resource)
              .withSubresource(subresource.isEmpty() ? null : subresource)
          .endResourceAttributes().endSpec().build();
      SelfSubjectAccessReview answer =
          client.authorization().v1().selfSubjectAccessReview().create(review);
      return answer.getStatus() != null && Boolean.TRUE.equals(answer.getStatus().getAllowed());
    } catch (RuntimeException exception) {
      return true; // cannot tell; the real call reports a precise error
    }
  }

  static String brief(Throwable exception) {
    Throwable root = exception;
    while (root.getCause() != null && root.getCause() != root) root = root.getCause();
    String message = exception.getMessage() == null ? root.toString() : exception.getMessage();
    message = message.replaceAll("[\\r\\n]+", " ");
    return message.substring(0, Math.min(300, message.length()));
  }

  @Override
  public Optional<PodInfo> pod(ClusterConfig cluster, String namespace, String name) {
    Pod pod = client(cluster).pods().inNamespace(namespace).withName(name).get();
    if (pod == null) return Optional.empty();
    String phase = pod.getStatus() == null ? null : pod.getStatus().getPhase();
    boolean ready = false;
    String reason = null;
    String message = null;
    if (pod.getStatus() != null && pod.getStatus().getConditions() != null) {
      for (PodCondition condition : pod.getStatus().getConditions()) {
        if ("Ready".equals(condition.getType()) && "True".equals(condition.getStatus())) ready = true;
        if ("PodScheduled".equals(condition.getType()) && "False".equals(condition.getStatus())) {
          reason = condition.getReason();
          message = condition.getMessage();
        }
      }
    }
    String imageId = null;
    String node = pod.getSpec() == null ? null : pod.getSpec().getNodeName();
    if (pod.getStatus() != null && pod.getStatus().getContainerStatuses() != null
        && !pod.getStatus().getContainerStatuses().isEmpty()) {
      ContainerStatus status = pod.getStatus().getContainerStatuses().get(0);
      imageId = status.getImageID();
      if (status.getState() != null && status.getState().getWaiting() != null) {
        reason = status.getState().getWaiting().getReason();
        message = status.getState().getWaiting().getMessage();
      }
    }
    String image = pod.getSpec() == null || pod.getSpec().getContainers().isEmpty()
        ? null : pod.getSpec().getContainers().get(0).getImage();
    return Optional.of(new PodInfo(phase, ready, node, image, imageId, reason, message,
        pod.getMetadata().getDeletionTimestamp() != null));
  }

  @Override
  public void applySecret(ClusterConfig cluster, Secret secret) {
    client(cluster).secrets().inNamespace(secret.getMetadata().getNamespace())
        .resource(secret).createOrReplace();
  }

  @Override
  public void createPvcIfAbsent(ClusterConfig cluster, PersistentVolumeClaim claim) {
    var operation = client(cluster).persistentVolumeClaims()
        .inNamespace(claim.getMetadata().getNamespace());
    if (operation.withName(claim.getMetadata().getName()).get() == null) operation.resource(claim).create();
  }

  @Override
  public void createPod(ClusterConfig cluster, Pod pod) {
    client(cluster).pods().inNamespace(pod.getMetadata().getNamespace()).resource(pod).create();
  }

  @Override
  public void deletePod(ClusterConfig cluster, String namespace, String name) {
    client(cluster).pods().inNamespace(namespace).withName(name).withGracePeriod(3).delete();
  }

  @Override
  public void deletePvc(ClusterConfig cluster, String namespace, String name) {
    client(cluster).persistentVolumeClaims().inNamespace(namespace).withName(name).delete();
  }

  @Override
  public void deleteSecret(ClusterConfig cluster, String namespace, String name) {
    client(cluster).secrets().inNamespace(namespace).withName(name).delete();
  }

  @Override
  public String exec(ClusterConfig cluster, String namespace, String pod, String container,
      Duration timeout, String... command) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    try (ExecWatch watch = client(cluster).pods().inNamespace(namespace).withName(pod)
        .inContainer(container).writingOutput(out).writingError(err).exec(command)) {
      int code = watch.exitCode().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
      if (code != 0) {
        throw new IOException("Command in the pod failed (" + code + "): "
            + err.toString(StandardCharsets.UTF_8).strip());
      }
      return out.toString(StandardCharsets.UTF_8);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while running a command in the pod", exception);
    } catch (ExecutionException | TimeoutException exception) {
      throw new IOException("Command in the pod did not finish: " + brief(exception), exception);
    }
  }

  @Override
  public void install(ClusterConfig cluster, String namespace, String pod, String container,
      byte[] data, String script, Duration timeout) throws IOException {
    ByteArrayOutputStream err = new ByteArrayOutputStream();
    try (ExecWatch watch = client(cluster).pods().inNamespace(namespace).withName(pod)
        .inContainer(container).redirectingInput().writingOutput(err).writingError(err)
        .exec("sh", "-c", script)) {
      OutputStream input = watch.getInput();
      // One frame on purpose: the JDK WebSocket allows a single outstanding send and fabric8 does not
      // queue behind it, so writing in chunks fails with "could not send" on the second chunk.
      input.write(data, 0, data.length);
      input.flush();
      int code = watch.exitCode().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
      if (code != 0) {
        throw new IOException("Copying the workspace agent into the pod failed (" + code + "): "
            + err.toString(StandardCharsets.UTF_8).strip());
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while copying the workspace agent", exception);
    } catch (ExecutionException | TimeoutException exception) {
      throw new IOException("Copying the workspace agent did not finish: " + brief(exception), exception);
    }
  }

  @Override
  public AgentResponse call(ClusterConfig cluster, String namespace, String pod, int port,
      String method, String path, Map<String, String> headers, byte[] body, String contentType)
      throws IOException {
    return send(cluster, namespace, pod, port, method, path, headers,
        body == null ? null : new ByteArrayInputStream(body), body == null ? 0 : body.length,
        contentType, null);
  }

  @Override
  public AgentResponse call(ClusterConfig cluster, String namespace, String pod, int port,
      String method, String path, Map<String, String> headers, InputStream body, long length,
      String contentType) throws IOException {
    // The agent answers only after unpacking a large bundle, so this request may legitimately run
    // far longer than the 30 seconds that apply to ordinary calls.
    return send(cluster, namespace, pod, port, method, path, headers, body, length, contentType,
        Duration.ofMinutes(30));
  }

  private AgentResponse send(ClusterConfig cluster, String namespace, String pod, int port,
      String method, String path, Map<String, String> headers, InputStream body, long length,
      String contentType, Duration timeout) throws IOException {
    KubernetesClient client = client(cluster);
    String base = client.getMasterUrl().toString().replaceAll("/+$", "");
    String url = base + "/api/v1/namespaces/" + namespace + "/pods/" + pod + ":" + port + "/proxy" + path;
    HttpRequest.Builder builder = client.getHttpClient().newHttpRequestBuilder().uri(url).forStreaming();
    headers.forEach(builder::setHeader);
    if (timeout != null) builder.timeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
    if (body != null) {
      builder.method(method, contentType, body, length);
    } else {
      builder.method(method, null, (String) null);
    }
    try {
      var response = client.getHttpClient().sendAsync(builder.build(), InputStream.class).get();
      return new AgentResponse(response.code(), response.body());
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while calling the workspace agent", exception);
    } catch (ExecutionException exception) {
      throw new WorkspaceHostUnavailableException(
          "The workspace pod could not be reached: " + brief(exception), exception);
    }
  }
}
