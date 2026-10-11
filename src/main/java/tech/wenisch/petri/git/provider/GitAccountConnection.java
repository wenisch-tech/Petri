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
@Table(name = "git_account_connection")
public class GitAccountConnection {
  @Id private UUID id;

  @Column(name = "provider_instance_id", nullable = false)
  private UUID providerInstanceId;

  @Column(name = "owner_id", nullable = false)
  private UUID ownerId;

  @Enumerated(EnumType.STRING)
  @Column(name = "auth_method", nullable = false, length = 16)
  private GitConnectionAuthMethod authMethod;

  @Column(name = "external_account_id", nullable = false, length = 255)
  private String externalAccountId;

  @Column(name = "external_login", nullable = false, length = 320)
  private String externalLogin;

  @Column(name = "display_name", length = 320)
  private String displayName;

  @Column(name = "access_token_ciphertext")
  private String accessTokenCiphertext;

  @Column(name = "refresh_token_ciphertext")
  private String refreshTokenCiphertext;

  @Column(name = "access_expires_at")
  private Instant accessExpiresAt;

  @Column(name = "refresh_expires_at")
  private Instant refreshExpiresAt;

  @Column(name = "granted_scopes", length = 1000)
  private String grantedScopes;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 24)
  private GitConnectionStatus status;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected GitAccountConnection() {}

  public GitAccountConnection(
      UUID id,
      UUID providerInstanceId,
      UUID ownerId,
      GitConnectionAuthMethod authMethod,
      ProviderIdentity identity,
      String accessTokenCiphertext,
      String refreshTokenCiphertext,
      Instant accessExpiresAt,
      Instant refreshExpiresAt,
      String grantedScopes,
      Instant now) {
    this.id = id;
    this.providerInstanceId = providerInstanceId;
    this.ownerId = ownerId;
    this.authMethod = authMethod;
    this.externalAccountId = identity.id();
    this.externalLogin = identity.login();
    this.displayName = identity.displayName();
    reconnect(authMethod, identity, accessTokenCiphertext, refreshTokenCiphertext,
        accessExpiresAt, refreshExpiresAt, grantedScopes, now);
    this.createdAt = now;
  }

  public void reconnect(
      GitConnectionAuthMethod authMethod,
      ProviderIdentity identity,
      String accessTokenCiphertext,
      String refreshTokenCiphertext,
      Instant accessExpiresAt,
      Instant refreshExpiresAt,
      String grantedScopes,
      Instant now) {
    this.authMethod = authMethod;
    this.externalAccountId = identity.id();
    this.externalLogin = identity.login();
    this.displayName = identity.displayName();
    this.accessTokenCiphertext = accessTokenCiphertext;
    this.refreshTokenCiphertext = refreshTokenCiphertext;
    this.accessExpiresAt = accessExpiresAt;
    this.refreshExpiresAt = refreshExpiresAt;
    this.grantedScopes = grantedScopes;
    this.status = GitConnectionStatus.ACTIVE;
    this.updatedAt = now;
  }

  public void disconnect(Instant now) {
    this.accessTokenCiphertext = null;
    this.refreshTokenCiphertext = null;
    this.accessExpiresAt = null;
    this.refreshExpiresAt = null;
    this.status = GitConnectionStatus.DISCONNECTED;
    this.updatedAt = now;
  }

  public void mark(GitConnectionStatus status, Instant now) {
    this.status = status;
    this.updatedAt = now;
  }

  public void replaceTokens(
      String accessTokenCiphertext,
      String refreshTokenCiphertext,
      Instant accessExpiresAt,
      Instant refreshExpiresAt,
      String grantedScopes,
      Instant now) {
    this.accessTokenCiphertext = accessTokenCiphertext;
    if (refreshTokenCiphertext != null) this.refreshTokenCiphertext = refreshTokenCiphertext;
    this.accessExpiresAt = accessExpiresAt;
    if (refreshExpiresAt != null) this.refreshExpiresAt = refreshExpiresAt;
    if (grantedScopes != null) this.grantedScopes = grantedScopes;
    this.status = GitConnectionStatus.ACTIVE;
    this.updatedAt = now;
  }

  public UUID getId() { return id; }
  public UUID getProviderInstanceId() { return providerInstanceId; }
  public UUID getOwnerId() { return ownerId; }
  public GitConnectionAuthMethod getAuthMethod() { return authMethod; }
  public String getExternalAccountId() { return externalAccountId; }
  public String getExternalLogin() { return externalLogin; }
  public String getDisplayName() { return displayName; }
  String accessTokenCiphertext() { return accessTokenCiphertext; }
  String refreshTokenCiphertext() { return refreshTokenCiphertext; }
  public Instant getAccessExpiresAt() { return accessExpiresAt; }
  public Instant getRefreshExpiresAt() { return refreshExpiresAt; }
  public String getGrantedScopes() { return grantedScopes; }
  public GitConnectionStatus getStatus() { return status; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
}
