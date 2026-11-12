package tech.wenisch.petri.web;

import tech.wenisch.petri.kubernetes.KubernetesSettingsService;

/** The settings form of Kubernetes workspaces, shared by the admin page and the project tab. */
public record KubernetesForm(
    Boolean enabled, Boolean clusterOverride, String authMode, String kubeconfig,
    Boolean clearKubeconfig, String apiServerUrl, String token, Boolean clearToken, String caPem,
    Boolean insecure, String namespace, String image, String pvcSize, String storageClass,
    String cpuRequest, String cpuLimit, String memoryRequest, String memoryLimit, Long runAsUser,
    Long fsGroup, Integer idleTimeoutMinutes, String toolchain) {

  /** An unchecked checkbox is not sent at all. */
  private static boolean on(Boolean value) {
    return Boolean.TRUE.equals(value);
  }

  KubernetesSettingsService.Save toSave() {
    return new KubernetesSettingsService.Save(on(enabled), on(clusterOverride), authMode, kubeconfig,
        on(clearKubeconfig), apiServerUrl, token, on(clearToken), caPem, on(insecure), namespace, image, pvcSize,
        storageClass, cpuRequest, cpuLimit, memoryRequest, memoryLimit, runAsUser, fsGroup,
        idleTimeoutMinutes, toolchain);
  }
}
