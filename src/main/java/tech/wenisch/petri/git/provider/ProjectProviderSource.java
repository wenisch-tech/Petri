package tech.wenisch.petri.git.provider;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "project_provider_source")
public class ProjectProviderSource {
  @Id
  @Column(name = "project_id")
  private UUID projectId;

  @Column(name = "provider_instance_id", nullable = false)
  private UUID providerInstanceId;

  @Column(name = "connection_id", nullable = false)
  private UUID connectionId;

  @Column(name = "owner_id", nullable = false)
  private UUID ownerId;

  @Column(name = "external_repository_id", nullable = false, length = 255)
  private String externalRepositoryId;

  @Column(name = "full_name", nullable = false, length = 500)
  private String fullName;

  @Column(name = "web_url", length = 2048)
  private String webUrl;

  @Column(length = 32)
  private String visibility;

  @Column(name = "can_write", nullable = false)
  private boolean canWrite;

  @Column(nullable = false)
  private boolean archived;

  @Enumerated(EnumType.STRING)
  @Column(name = "credential_mode", nullable = false, length = 24)
  private ProjectCredentialMode credentialMode;

  @Column(name = "imported_at", nullable = false)
  private Instant importedAt;

  protected ProjectProviderSource() {}

  public ProjectProviderSource(
      UUID projectId, UUID providerInstanceId, UUID connectionId, UUID ownerId,
      ProviderRepository repository, Instant importedAt) {
    this.projectId = projectId;
    this.providerInstanceId = providerInstanceId;
    this.connectionId = connectionId;
    this.ownerId = ownerId;
    this.externalRepositoryId = repository.id();
    this.fullName = repository.fullName();
    this.webUrl = repository.webUrl();
    this.visibility = repository.visibility();
    this.canWrite = repository.canWrite();
    this.archived = repository.archived();
    this.credentialMode = ProjectCredentialMode.CONNECTION;
    this.importedAt = importedAt;
  }

  public void useSimpleGitCredentials() { this.credentialMode = ProjectCredentialMode.SIMPLE_GIT; }
  public UUID getProjectId() { return projectId; }
  public UUID getProviderInstanceId() { return providerInstanceId; }
  public UUID getConnectionId() { return connectionId; }
  public UUID getOwnerId() { return ownerId; }
  public String getExternalRepositoryId() { return externalRepositoryId; }
  public String getFullName() { return fullName; }
  public String getWebUrl() { return webUrl; }
  public String getVisibility() { return visibility; }
  public boolean isCanWrite() { return canWrite; }
  public boolean isArchived() { return archived; }
  public ProjectCredentialMode getCredentialMode() { return credentialMode; }
  public Instant getImportedAt() { return importedAt; }
}
