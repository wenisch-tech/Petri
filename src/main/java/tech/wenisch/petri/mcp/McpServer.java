package tech.wenisch.petri.mcp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** A remote MCP server of one user, or an instance-wide one. Secrets are encrypted; the page never sees them. */
@Entity
@Table(name = "mcp_server")
class McpServer {
  @Id private UUID id;

  /** Null for an instance-wide server managed by an administrator. */
  @Column(name = "owner_id")
  private UUID ownerId;

  @Column(nullable = false, length = 80)
  private String name;

  @Column(nullable = false, length = 2048)
  private String url;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 24)
  private McpTransport transport;

  @Enumerated(EnumType.STRING)
  @Column(name = "auth_type", nullable = false, length = 16)
  private McpAuthType authType;

  @Column(name = "secret_ciphertext", columnDefinition = "TEXT")
  private String secretCiphertext;

  @Column(name = "oauth_client_id", length = 512)
  private String oauthClientId;

  @Column(name = "oauth_client_secret_ciphertext", columnDefinition = "TEXT")
  private String oauthClientSecretCiphertext;

  @Column(name = "oauth_scopes", length = 512)
  private String oauthScopes;

  @Column(name = "oauth_dynamic_client", nullable = false)
  private boolean oauthDynamicClient;

  @Column(name = "oauth_authorization_url", length = 2048)
  private String oauthAuthorizationUrl;

  @Column(name = "oauth_token_url", length = 2048)
  private String oauthTokenUrl;

  @Column(name = "oauth_registration_url", length = 2048)
  private String oauthRegistrationUrl;

  @Column(name = "oauth_resource", length = 2048)
  private String oauthResource;

  @Column(name = "oauth_access_ciphertext", columnDefinition = "TEXT")
  private String oauthAccessCiphertext;

  @Column(name = "oauth_refresh_ciphertext", columnDefinition = "TEXT")
  private String oauthRefreshCiphertext;

  @Column(name = "oauth_expires_at")
  private Instant oauthExpiresAt;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 16)
  private McpServerStatus status;

  @Column(name = "last_error", length = 500)
  private String lastError;

  @Column(name = "tools_json", columnDefinition = "TEXT")
  private String toolsJson;

  @Column(name = "tools_refreshed_at")
  private Instant toolsRefreshedAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected McpServer() {}

  McpServer(UUID id, UUID ownerId, Instant now) {
    this.id = id;
    this.ownerId = ownerId;
    this.status = McpServerStatus.ACTIVE;
    this.createdAt = now;
    this.updatedAt = now;
  }

  void configure(String name, String url, McpTransport transport, McpAuthType authType, Instant now) {
    this.name = name;
    this.url = url;
    this.transport = transport;
    this.authType = authType;
    this.updatedAt = now;
  }

  void secret(String ciphertext) { this.secretCiphertext = ciphertext; }

  void oauthClient(String clientId, String clientSecretCiphertext, String scopes) {
    this.oauthClientId = clientId;
    this.oauthClientSecretCiphertext = clientSecretCiphertext;
    this.oauthScopes = scopes;
  }

  void oauthDynamicClient(boolean value) { this.oauthDynamicClient = value; }

  boolean oauthDynamicClient() { return oauthDynamicClient; }

  void oauthEndpoints(String authorization, String token, String registration, String resource) {
    this.oauthAuthorizationUrl = authorization;
    this.oauthTokenUrl = token;
    this.oauthRegistrationUrl = registration;
    this.oauthResource = resource;
  }

  void oauthTokens(String accessCiphertext, String refreshCiphertext, Instant expiresAt) {
    this.oauthAccessCiphertext = accessCiphertext;
    if (refreshCiphertext != null) this.oauthRefreshCiphertext = refreshCiphertext;
    this.oauthExpiresAt = expiresAt;
  }

  void clearOauthTokens() {
    this.oauthAccessCiphertext = null;
    this.oauthRefreshCiphertext = null;
    this.oauthExpiresAt = null;
  }

  void mark(McpServerStatus status, String error, Instant now) {
    this.status = status;
    this.lastError = error == null ? null : (error.length() > 500 ? error.substring(0, 500) : error);
    this.updatedAt = now;
  }

  void tools(String json, Instant now) {
    this.toolsJson = json;
    this.toolsRefreshedAt = now;
  }

  UUID id() { return id; }
  UUID ownerId() { return ownerId; }
  boolean global() { return ownerId == null; }
  String name() { return name; }
  String url() { return url; }
  McpTransport transport() { return transport; }
  McpAuthType authType() { return authType; }
  String secretCiphertext() { return secretCiphertext; }
  String oauthClientId() { return oauthClientId; }
  String oauthClientSecretCiphertext() { return oauthClientSecretCiphertext; }
  String oauthScopes() { return oauthScopes; }
  String oauthAuthorizationUrl() { return oauthAuthorizationUrl; }
  String oauthTokenUrl() { return oauthTokenUrl; }
  String oauthRegistrationUrl() { return oauthRegistrationUrl; }
  String oauthResource() { return oauthResource; }
  String oauthAccessCiphertext() { return oauthAccessCiphertext; }
  String oauthRefreshCiphertext() { return oauthRefreshCiphertext; }
  Instant oauthExpiresAt() { return oauthExpiresAt; }
  McpServerStatus status() { return status; }
  String lastError() { return lastError; }
  String toolsJson() { return toolsJson; }
  Instant toolsRefreshedAt() { return toolsRefreshedAt; }
  Instant createdAt() { return createdAt; }
}
