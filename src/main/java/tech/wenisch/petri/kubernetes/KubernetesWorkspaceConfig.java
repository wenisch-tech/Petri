package tech.wenisch.petri.kubernetes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * Settings of Kubernetes workspaces. The row with a null project is the instance default; a
 * project row holds the project's switch and overrides, and its null columns inherit the default.
 */
@Entity
@Table(name = "kubernetes_workspace_config")
public class KubernetesWorkspaceConfig {
  static final UUID INSTANCE_ID = UUID.fromString("00000000-0000-4000-8000-000000000044");

  @Id private UUID id;
  @Column(name = "project_id") private UUID projectId;
  @Column(nullable = false) private boolean enabled;
  @Column(name = "cluster_override", nullable = false) private boolean clusterOverride;
  @Column(name = "auth_mode", length = 16) private String authMode;
  @Column(name = "kubeconfig_ciphertext", columnDefinition = "TEXT") private String kubeconfigCiphertext;
  @Column(name = "api_server_url", length = 500) private String apiServerUrl;
  @Column(name = "token_ciphertext", columnDefinition = "TEXT") private String tokenCiphertext;
  @Column(name = "ca_pem", columnDefinition = "TEXT") private String caPem;
  @Column(nullable = false) private boolean insecure;
  @Column(length = 63) private String namespace;
  @Column(length = 500) private String image;
  @Column(name = "pvc_size", length = 32) private String pvcSize;
  @Column(name = "storage_class", length = 253) private String storageClass;
  @Column(name = "cpu_request", length = 32) private String cpuRequest;
  @Column(name = "cpu_limit", length = 32) private String cpuLimit;
  @Column(name = "memory_request", length = 32) private String memoryRequest;
  @Column(name = "memory_limit", length = 32) private String memoryLimit;
  @Column(name = "run_as_user") private Long runAsUser;
  @Column(name = "fs_group") private Long fsGroup;
  @Column(name = "idle_timeout_minutes") private Integer idleTimeoutMinutes;
  @Column(name = "toolchain_mode") private String toolchainMode;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;
  @Column(name = "updated_by", length = 320) private String updatedBy;

  protected KubernetesWorkspaceConfig() {}

  static KubernetesWorkspaceConfig instance(Instant now) {
    KubernetesWorkspaceConfig value = new KubernetesWorkspaceConfig();
    value.id = INSTANCE_ID;
    value.updatedAt = now;
    return value;
  }

  static KubernetesWorkspaceConfig forProject(UUID projectId, Instant now) {
    KubernetesWorkspaceConfig value = new KubernetesWorkspaceConfig();
    value.id = UUID.randomUUID();
    value.projectId = projectId;
    value.updatedAt = now;
    return value;
  }

  /** Replaces every non-secret setting. Secrets are changed through their own methods. */
  void update(
      boolean enabled, boolean clusterOverride, String authMode, String apiServerUrl, String caPem,
      boolean insecure, String namespace, String image, String pvcSize, String storageClass,
      String cpuRequest, String cpuLimit, String memoryRequest, String memoryLimit, Long runAsUser,
      Long fsGroup, Integer idleTimeoutMinutes, String toolchainMode, String actor, Instant now) {
    this.enabled = enabled;
    this.clusterOverride = clusterOverride;
    this.authMode = authMode;
    this.apiServerUrl = apiServerUrl;
    this.caPem = caPem;
    this.insecure = insecure;
    this.namespace = namespace;
    this.image = image;
    this.pvcSize = pvcSize;
    this.storageClass = storageClass;
    this.cpuRequest = cpuRequest;
    this.cpuLimit = cpuLimit;
    this.memoryRequest = memoryRequest;
    this.memoryLimit = memoryLimit;
    this.runAsUser = runAsUser;
    this.fsGroup = fsGroup;
    this.idleTimeoutMinutes = idleTimeoutMinutes;
    this.toolchainMode = toolchainMode;
    this.updatedBy = actor;
    this.updatedAt = now;
  }

  void kubeconfigCiphertext(String value) { this.kubeconfigCiphertext = value; }
  void tokenCiphertext(String value) { this.tokenCiphertext = value; }

  public UUID getId() { return id; }
  public UUID getProjectId() { return projectId; }
  public boolean isEnabled() { return enabled; }
  public boolean isClusterOverride() { return clusterOverride; }
  public String getAuthMode() { return authMode; }
  public String getKubeconfigCiphertext() { return kubeconfigCiphertext; }
  public boolean isKubeconfigSet() { return kubeconfigCiphertext != null; }
  public String getApiServerUrl() { return apiServerUrl; }
  public String getTokenCiphertext() { return tokenCiphertext; }
  public boolean isTokenSet() { return tokenCiphertext != null; }
  public String getCaPem() { return caPem; }
  public boolean isInsecure() { return insecure; }
  public String getNamespace() { return namespace; }
  public String getImage() { return image; }
  public String getPvcSize() { return pvcSize; }
  public String getStorageClass() { return storageClass; }
  public String getCpuRequest() { return cpuRequest; }
  public String getCpuLimit() { return cpuLimit; }
  public String getMemoryRequest() { return memoryRequest; }
  public String getMemoryLimit() { return memoryLimit; }
  public Long getRunAsUser() { return runAsUser; }
  public Long getFsGroup() { return fsGroup; }
  public Integer getIdleTimeoutMinutes() { return idleTimeoutMinutes; }
  public String getToolchainMode() { return toolchainMode; }
  public Instant getUpdatedAt() { return updatedAt; }
  public String getUpdatedBy() { return updatedBy; }
}
