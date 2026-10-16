package tech.wenisch.petri.mcp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A user's own credential (token or headers) for an instance-wide MCP server. Encrypted. */
@Entity
@Table(name = "mcp_user_credential")
@IdClass(McpUserCredential.Key.class)
class McpUserCredential {
  @Id @Column(name = "server_id") private UUID serverId;
  @Id @Column(name = "user_id") private UUID userId;
  @Column(name = "secret_ciphertext", nullable = false, columnDefinition = "TEXT") private String secretCiphertext;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;

  protected McpUserCredential() {}

  McpUserCredential(UUID serverId, UUID userId, String secretCiphertext, Instant now) {
    this.serverId = serverId;
    this.userId = userId;
    this.secretCiphertext = secretCiphertext;
    this.updatedAt = now;
  }

  void secret(String ciphertext, Instant now) {
    this.secretCiphertext = ciphertext;
    this.updatedAt = now;
  }

  String secretCiphertext() { return secretCiphertext; }

  static class Key implements Serializable {
    private UUID serverId;
    private UUID userId;

    public Key() {}

    public Key(UUID serverId, UUID userId) {
      this.serverId = serverId;
      this.userId = userId;
    }

    @Override
    public boolean equals(Object other) {
      return other instanceof Key key
          && Objects.equals(serverId, key.serverId) && Objects.equals(userId, key.userId);
    }

    @Override
    public int hashCode() { return Objects.hash(serverId, userId); }
  }
}
