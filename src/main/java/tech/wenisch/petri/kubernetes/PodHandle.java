package tech.wenisch.petri.kubernetes;

import java.util.UUID;

/** A running workspace pod and what is needed to talk to its agent. */
public record PodHandle(
    UUID projectId, ClusterConfig cluster, String namespace, String podName, String agentToken,
    ToolchainMode toolchain) {

  public PodHandle(
      UUID projectId, ClusterConfig cluster, String namespace, String podName, String agentToken) {
    this(projectId, cluster, namespace, podName, agentToken, ToolchainMode.OFF);
  }

  public PodHandle withToolchain(ToolchainMode mode) {
    return new PodHandle(projectId, cluster, namespace, podName, agentToken, mode);
  }

  @Override
  public String toString() {
    return "PodHandle[" + namespace + "/" + podName + "]";
  }
}
