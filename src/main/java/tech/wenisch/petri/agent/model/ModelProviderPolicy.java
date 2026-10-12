package tech.wenisch.petri.agent.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** Single-row instance policy for model providers. */
@Entity
@Table(name = "model_provider_policy")
class ModelProviderPolicy {
  static final int SINGLETON_ID = 1;

  @Id private Integer id;

  @Column(name = "allow_user_providers", nullable = false)
  private boolean allowUserProviders;

  @Column(name = "updated_by")
  private UUID updatedBy;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected ModelProviderPolicy() {}

  ModelProviderPolicy(boolean allowUserProviders, UUID actorId, Instant now) {
    this.id = SINGLETON_ID;
    update(allowUserProviders, actorId, now);
  }

  void update(boolean allowUserProviders, UUID actorId, Instant now) {
    this.allowUserProviders = allowUserProviders;
    this.updatedBy = actorId;
    this.updatedAt = now;
  }

  boolean allowUserProviders() { return allowUserProviders; }
}
