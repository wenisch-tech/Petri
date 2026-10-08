package tech.wenisch.petri.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_event")
public class AuditEvent {
  @Id private UUID id;

  @Column(name = "occurred_at", nullable = false)
  private Instant occurredAt;

  @Column(name = "actor_email", nullable = false, length = 320)
  private String actorEmail;

  @Column(name = "event_action", nullable = false, length = 80)
  private String action;

  @Column(name = "target_type", nullable = false, length = 80)
  private String targetType;

  @Column(name = "target_id", nullable = false)
  private UUID targetId;

  @Column(nullable = false, length = 1000)
  private String summary;

  protected AuditEvent() {}

  public AuditEvent(
      UUID id,
      Instant occurredAt,
      String actorEmail,
      String action,
      String targetType,
      UUID targetId,
      String summary) {
    this.id = id;
    this.occurredAt = occurredAt;
    this.actorEmail = actorEmail;
    this.action = action;
    this.targetType = targetType;
    this.targetId = targetId;
    this.summary = summary;
  }

  public UUID getId() { return id; }
  public Instant getOccurredAt() { return occurredAt; }
  public String getActorEmail() { return actorEmail; }
  public String getAction() { return action; }
  public String getTargetType() { return targetType; }
  public UUID getTargetId() { return targetId; }
  public String getSummary() { return summary; }
}
