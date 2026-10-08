package tech.wenisch.petri.security.oidc;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "user_identity")
public class UserIdentity {
  @Id private UUID id;

  @Column(name = "user_id", nullable = false)
  private UUID userId;

  @Column(name = "provider_registration_id", nullable = false, length = 100)
  private String providerRegistrationId;

  @Column(nullable = false, length = 255)
  private String subject;

  @Column(name = "linked_at", nullable = false)
  private Instant linkedAt;

  protected UserIdentity() {}

  public UserIdentity(
      UUID id, UUID userId, String providerRegistrationId, String subject, Instant linkedAt) {
    this.id = id;
    this.userId = userId;
    this.providerRegistrationId = providerRegistrationId;
    this.subject = subject;
    this.linkedAt = linkedAt;
  }

  public UUID getId() { return id; }
  public UUID getUserId() { return userId; }
  public String getProviderRegistrationId() { return providerRegistrationId; }
  public String getSubject() { return subject; }
  public Instant getLinkedAt() { return linkedAt; }
}
