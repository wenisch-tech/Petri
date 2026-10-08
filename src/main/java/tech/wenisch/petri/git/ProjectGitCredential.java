package tech.wenisch.petri.git;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "project_git_credential")
class ProjectGitCredential {
  @Id private UUID id;

  @Column(name = "project_id", nullable = false, unique = true)
  private UUID projectId;

  @Enumerated(EnumType.STRING)
  @Column(name = "authentication_type", nullable = false, length = 32)
  private GitAuthenticationType authenticationType;

  @Column(length = 320)
  private String username;

  @Column(name = "secret_ciphertext", nullable = false, length = 16384)
  private String secretCiphertext;

  @Column(name = "created_by", nullable = false)
  private UUID createdBy;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_by", nullable = false)
  private UUID updatedBy;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected ProjectGitCredential() {}

  ProjectGitCredential(
      UUID id,
      UUID projectId,
      GitAuthenticationType authenticationType,
      String username,
      String secretCiphertext,
      UUID actorId,
      Instant now) {
    this.id = id;
    this.projectId = projectId;
    this.authenticationType = authenticationType;
    this.username = username;
    this.secretCiphertext = secretCiphertext;
    this.createdBy = actorId;
    this.createdAt = now;
    this.updatedBy = actorId;
    this.updatedAt = now;
  }

  void replace(
      GitAuthenticationType authenticationType,
      String username,
      String secretCiphertext,
      UUID actorId,
      Instant now) {
    this.authenticationType = authenticationType;
    this.username = username;
    this.secretCiphertext = secretCiphertext;
    this.updatedBy = actorId;
    this.updatedAt = now;
  }

  UUID id() { return id; }
  GitAuthenticationType authenticationType() { return authenticationType; }
  String username() { return username; }
  String secretCiphertext() { return secretCiphertext; }
  Instant updatedAt() { return updatedAt; }
}
