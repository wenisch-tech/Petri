package tech.wenisch.petri.apikey;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "api_key")
public class ApiKey {
  @Id private UUID id;

  @Column(nullable = false, length = 120)
  private String name;

  @Column(name = "key_identifier", nullable = false, unique = true, length = 32)
  private String keyIdentifier;

  @Column(name = "secret_hash", nullable = false, length = 200)
  private String secretHash;

  @Column(name = "created_by", nullable = false)
  private UUID createdBy;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "last_used_at")
  private Instant lastUsedAt;

  @Column(name = "expires_at")
  private Instant expiresAt;

  @Column(name = "revoked_at")
  private Instant revokedAt;

  @Column(name = "revoked_by")
  private UUID revokedBy;

  protected ApiKey() {}

  public ApiKey(
      UUID id,
      String name,
      String keyIdentifier,
      String secretHash,
      UUID createdBy,
      Instant createdAt,
      Instant expiresAt) {
    this.id = id;
    this.name = name;
    this.keyIdentifier = keyIdentifier;
    this.secretHash = secretHash;
    this.createdBy = createdBy;
    this.createdAt = createdAt;
    this.expiresAt = expiresAt;
  }

  boolean isActiveAt(Instant instant) {
    return revokedAt == null && (expiresAt == null || instant.isBefore(expiresAt));
  }

  void markUsed(Instant instant) {
    this.lastUsedAt = instant;
  }

  boolean revoke(Instant instant, UUID actorId) {
    if (revokedAt != null) return false;
    this.revokedAt = instant;
    this.revokedBy = actorId;
    return true;
  }

  String secretHash() { return secretHash; }
  public UUID getId() { return id; }
  public String getName() { return name; }
  public String getKeyIdentifier() { return keyIdentifier; }
  public UUID getCreatedBy() { return createdBy; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getLastUsedAt() { return lastUsedAt; }
  public Instant getExpiresAt() { return expiresAt; }
  public Instant getRevokedAt() { return revokedAt; }
  public UUID getRevokedBy() { return revokedBy; }
}
