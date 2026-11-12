package tech.wenisch.petri.kubernetes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** What Petri knows about the pod, volume and credentials of one project's workspace. */
@Entity
@Table(name = "workspace_pod_state")
public class WorkspacePodState {
  @Id @Column(name = "project_id") private UUID projectId;
  @Column(nullable = false, length = 63) private String namespace;
  @Column(name = "pod_name", nullable = false, length = 63) private String podName;
  @Column(name = "pvc_name", nullable = false, length = 63) private String pvcName;
  @Column(name = "secret_name", nullable = false, length = 63) private String secretName;
  @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private WorkspacePodPhase phase;
  @Column(name = "agent_token_ciphertext", length = 2000) private String agentTokenCiphertext;
  @Column(name = "agent_arch", length = 16) private String agentArch;
  @Column(length = 500) private String image;
  @Column(name = "image_digest", length = 300) private String imageDigest;
  @Column(name = "node_name", length = 253) private String nodeName;
  @Column(name = "started_at") private Instant startedAt;
  @Column(name = "last_used_at") private Instant lastUsedAt;
  @Column(name = "last_error", length = 1000) private String lastError;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;

  protected WorkspacePodState() {}

  WorkspacePodState(UUID projectId, String namespace, Instant now) {
    this.projectId = projectId;
    this.namespace = namespace;
    this.podName = names(projectId);
    this.pvcName = names(projectId);
    this.secretName = names(projectId);
    this.phase = WorkspacePodPhase.STOPPED;
    this.updatedAt = now;
  }

  /** Resource names are derived from the project id so that a lost row can always be rebuilt. */
  static String names(UUID projectId) {
    return "petri-ws-" + projectId;
  }

  void namespace(String value, Instant now) { this.namespace = value; this.updatedAt = now; }

  void phase(WorkspacePodPhase value, Instant now) { this.phase = value; this.updatedAt = now; }

  void agentToken(String ciphertext) { this.agentTokenCiphertext = ciphertext; }

  void started(String arch, String image, String digest, String node, Instant now) {
    this.agentArch = arch;
    this.image = image;
    this.imageDigest = digest;
    this.nodeName = node;
    this.startedAt = now;
    this.lastUsedAt = now;
    this.lastError = null;
    this.phase = WorkspacePodPhase.RUNNING;
    this.updatedAt = now;
  }

  void failed(String message, Instant now) {
    this.lastError = message == null ? null : message.substring(0, Math.min(1000, message.length()));
    this.phase = WorkspacePodPhase.FAILED;
    this.updatedAt = now;
  }

  void stopped(Instant now) {
    this.phase = WorkspacePodPhase.STOPPED;
    this.nodeName = null;
    this.startedAt = null;
    this.updatedAt = now;
  }

  void touch(Instant now) { this.lastUsedAt = now; }

  public UUID getProjectId() { return projectId; }
  public String getNamespace() { return namespace; }
  public String getPodName() { return podName; }
  public String getPvcName() { return pvcName; }
  public String getSecretName() { return secretName; }
  public WorkspacePodPhase getPhase() { return phase; }
  public String getAgentTokenCiphertext() { return agentTokenCiphertext; }
  public String getAgentArch() { return agentArch; }
  public String getImage() { return image; }
  public String getImageDigest() { return imageDigest; }
  public String getNodeName() { return nodeName; }
  public Instant getStartedAt() { return startedAt; }
  public Instant getLastUsedAt() { return lastUsedAt; }
  public String getLastError() { return lastError; }
  public Instant getUpdatedAt() { return updatedAt; }
}
