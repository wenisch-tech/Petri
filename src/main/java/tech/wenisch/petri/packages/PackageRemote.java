package tech.wenisch.petri.packages;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** One mirror or registry of a package ecosystem; instance-wide when {@code projectId} is null. */
@Entity
@Table(name = "package_remote")
public class PackageRemote {
  @Id private UUID id;
  @Column(name = "project_id") private UUID projectId;
  @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private PackageEcosystem ecosystem;
  @Column(nullable = false, length = 120) private String name;
  @Column(nullable = false, length = 500) private String url;
  @Enumerated(EnumType.STRING) @Column(nullable = false, length = 16) private PackageRemoteMode mode;
  @Column(length = 200) private String username;
  @Column(name = "password_ciphertext", length = 2000) private String passwordCiphertext;
  @Column(name = "mirror_of", length = 120) private String mirrorOf;
  @Column(nullable = false) private boolean insecure;
  @Column(nullable = false) private boolean enabled;
  @Column(nullable = false) private int position;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;
  @Column(name = "updated_by", length = 320) private String updatedBy;

  protected PackageRemote() {}

  PackageRemote(UUID id, UUID projectId, Instant now) {
    this.id = id;
    this.projectId = projectId;
    this.createdAt = now;
  }

  void update(
      PackageEcosystem ecosystem, String name, String url, PackageRemoteMode mode, String username,
      String mirrorOf, boolean insecure, boolean enabled, int position, String actor, Instant now) {
    this.ecosystem = ecosystem;
    this.name = name;
    this.url = url;
    this.mode = mode;
    this.username = username;
    this.mirrorOf = mirrorOf;
    this.insecure = insecure;
    this.enabled = enabled;
    this.position = position;
    this.updatedBy = actor;
    this.updatedAt = now;
  }

  void password(String ciphertext) { this.passwordCiphertext = ciphertext; }

  public UUID getId() { return id; }
  public UUID getProjectId() { return projectId; }
  public PackageEcosystem getEcosystem() { return ecosystem; }
  public String getName() { return name; }
  public String getUrl() { return url; }
  public PackageRemoteMode getMode() { return mode; }
  public String getUsername() { return username; }
  public boolean isPasswordSet() { return passwordCiphertext != null; }
  String passwordCiphertext() { return passwordCiphertext; }
  public String getMirrorOf() { return mirrorOf; }
  public boolean isInsecure() { return insecure; }
  public boolean isEnabled() { return enabled; }
  public int getPosition() { return position; }
  public Instant getUpdatedAt() { return updatedAt; }
}
