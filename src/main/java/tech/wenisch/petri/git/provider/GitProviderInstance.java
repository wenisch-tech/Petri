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
@Table(name = "git_provider_instance")
public class GitProviderInstance {
  @Id private UUID id;

  @Enumerated(EnumType.STRING)
  @Column(name = "provider_type", nullable = false, length = 24)
  private GitProviderType providerType;

  @Column(name = "display_name", nullable = false, length = 120)
  private String displayName;

  @Column(name = "web_base_url", nullable = false, length = 2048)
  private String webBaseUrl;

  @Column(name = "owner_id")
  private UUID ownerId;

  @Column(name = "api_base_url", nullable = false, length = 2048)
  private String apiBaseUrl;

  @Column(name = "oauth_authorization_url", length = 2048)
  private String oauthAuthorizationUrl;

  @Column(name = "oauth_token_url", length = 2048)
  private String oauthTokenUrl;

  @Column(name = "oauth_scopes", length = 1000)
  private String oauthScopes;

  @Column(name = "oauth_client_id", length = 500)
  private String oauthClientId;

  @Column(name = "oauth_client_secret_ciphertext")
  private String oauthClientSecretCiphertext;

  @Column(nullable = false)
  private boolean enabled;

  @Column(name = "built_in", nullable = false)
  private boolean builtIn;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected GitProviderInstance() {}

  public GitProviderInstance(
      UUID id,
      GitProviderType providerType,
      String displayName,
      String webBaseUrl,
      String apiBaseUrl,
      String oauthAuthorizationUrl,
      String oauthTokenUrl,
      String oauthScopes,
      boolean enabled,
      Instant now) {
    this(id, providerType, displayName, webBaseUrl, apiBaseUrl, oauthAuthorizationUrl,
        oauthTokenUrl, oauthScopes, enabled, null, now);
  }

  public GitProviderInstance(
      UUID id,
      GitProviderType providerType,
      String displayName,
      String webBaseUrl,
      String apiBaseUrl,
      String oauthAuthorizationUrl,
      String oauthTokenUrl,
      String oauthScopes,
      boolean enabled,
      UUID ownerId,
      Instant now) {
    this.id = id;
    this.providerType = providerType;
    this.displayName = displayName;
    this.webBaseUrl = webBaseUrl;
    this.apiBaseUrl = apiBaseUrl;
    this.oauthAuthorizationUrl = oauthAuthorizationUrl;
    this.oauthTokenUrl = oauthTokenUrl;
    this.oauthScopes = oauthScopes;
    this.enabled = enabled;
    this.ownerId = ownerId;
    this.builtIn = false;
    this.createdAt = now;
    this.updatedAt = now;
  }

  public void update(
      String displayName,
      String webBaseUrl,
      String apiBaseUrl,
      String oauthAuthorizationUrl,
      String oauthTokenUrl,
      String oauthScopes,
      String oauthClientId,
      String oauthClientSecretCiphertext,
      boolean enabled,
      Instant now) {
    this.displayName = displayName;
    this.webBaseUrl = webBaseUrl;
    this.apiBaseUrl = apiBaseUrl;
    this.oauthAuthorizationUrl = oauthAuthorizationUrl;
    this.oauthTokenUrl = oauthTokenUrl;
    this.oauthScopes = oauthScopes;
    this.oauthClientId = oauthClientId;
    if (oauthClientSecretCiphertext != null) this.oauthClientSecretCiphertext = oauthClientSecretCiphertext;
    this.enabled = enabled;
    this.updatedAt = now;
  }

  public UUID getId() { return id; }
  public GitProviderType getProviderType() { return providerType; }
  public String getDisplayName() { return displayName; }
  public String getWebBaseUrl() { return webBaseUrl; }
  public UUID getOwnerId() { return ownerId; }
  public boolean isGlobal() { return ownerId == null; }
  public String getApiBaseUrl() { return apiBaseUrl; }
  public String getOauthAuthorizationUrl() { return oauthAuthorizationUrl; }
  public String getOauthTokenUrl() { return oauthTokenUrl; }
  public String getOauthScopes() { return oauthScopes; }
  public String getOauthClientId() { return oauthClientId; }
  String oauthClientSecretCiphertext() { return oauthClientSecretCiphertext; }
  public boolean isEnabled() { return enabled; }
  public boolean isBuiltIn() { return builtIn; }
  public boolean isOauthConfigured() {
    return oauthClientId != null && !oauthClientId.isBlank()
        && oauthClientSecretCiphertext != null && !oauthClientSecretCiphertext.isBlank();
  }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
}
