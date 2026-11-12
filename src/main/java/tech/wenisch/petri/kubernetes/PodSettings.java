package tech.wenisch.petri.kubernetes;

/** The settings of one project's workspace pod after the instance defaults were applied. */
public record PodSettings(
    String namespace, String image, String pvcSize, String storageClass, String cpuRequest,
    String cpuLimit, String memoryRequest, String memoryLimit, Long runAsUser, Long fsGroup,
    int idleTimeoutMinutes, ToolchainMode toolchain) {

  public static final String DEFAULT_PVC_SIZE = "10Gi";
  public static final int DEFAULT_IDLE_MINUTES = 60;
}
