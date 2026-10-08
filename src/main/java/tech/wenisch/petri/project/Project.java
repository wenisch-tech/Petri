package tech.wenisch.petri.project;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "agent_project")
public class Project {
  @Id private UUID id;

  @Column(nullable = false, length = 120)
  private String name;

  @Column(nullable = false, unique = true, length = 80)
  private String slug;

  @Column(name = "repository_uri", nullable = false, length = 2048)
  private String repositoryUri;

  @Column(name = "default_branch", nullable = false, length = 255)
  private String defaultBranch;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private ProjectStatus status;

  @Column(name = "created_by", nullable = false)
  private UUID createdBy;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected Project() {}

  public Project(
      UUID id,
      String name,
      String slug,
      String repositoryUri,
      String defaultBranch,
      UUID createdBy,
      Instant createdAt) {
    this.id = id;
    this.name = name;
    this.slug = slug;
    this.repositoryUri = repositoryUri;
    this.defaultBranch = defaultBranch;
    this.status = ProjectStatus.ACTIVE;
    this.createdBy = createdBy;
    this.createdAt = createdAt;
    this.updatedAt = createdAt;
  }

  public UUID getId() { return id; }
  public String getName() { return name; }
  public String getSlug() { return slug; }
  public String getRepositoryUri() { return repositoryUri; }
  public String getDefaultBranch() { return defaultBranch; }
  public ProjectStatus getStatus() { return status; }
  public UUID getCreatedBy() { return createdBy; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
}
