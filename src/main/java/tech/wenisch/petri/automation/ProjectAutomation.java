package tech.wenisch.petri.automation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "project_automation")
public class ProjectAutomation {
  @Id private UUID id;

  @Column(name = "project_id", nullable = false)
  private UUID projectId;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 32)
  private AutomationKind kind;

  @Column(nullable = false)
  private boolean enabled;

  @Enumerated(EnumType.STRING)
  @Column(name = "trigger_type", nullable = false, length = 32)
  private AutomationTrigger trigger;

  @Column(name = "cron_expression", length = 120)
  private String cronExpression;

  @Enumerated(EnumType.STRING)
  @Column(name = "post_mode", nullable = false, length = 16)
  private AutomationPostMode postMode;

  @Column(name = "provider_instance_id")
  private UUID providerInstanceId;

  @Column(length = 200)
  private String model;

  @Column(name = "max_model_turns", nullable = false)
  private int maxModelTurns;

  @Column(name = "last_polled_at")
  private Instant lastPolledAt;

  @Column(name = "last_cron_at")
  private Instant lastCronAt;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  protected ProjectAutomation() {}

  public ProjectAutomation(UUID id, UUID projectId, AutomationKind kind, Instant now) {
    this.id = id;
    this.projectId = projectId;
    this.kind = kind;
    this.enabled = false;
    this.trigger = AutomationTrigger.PULL_REQUEST;
    this.postMode = AutomationPostMode.AUTO;
    this.maxModelTurns = 20;
    this.createdAt = now;
    this.updatedAt = now;
  }

  public void update(
      boolean enabled, AutomationTrigger trigger, String cronExpression, AutomationPostMode postMode,
      UUID providerInstanceId, String model, int maxModelTurns, Instant now) {
    // Switching on starts a fresh baseline so existing pull requests are not reviewed in bulk.
    if (enabled && (!this.enabled || this.trigger != trigger)) {
      this.lastPolledAt = now;
      this.lastCronAt = now;
    }
    this.enabled = enabled;
    this.trigger = trigger;
    this.cronExpression = cronExpression;
    this.postMode = postMode;
    this.providerInstanceId = providerInstanceId;
    this.model = model;
    this.maxModelTurns = maxModelTurns;
    this.updatedAt = now;
  }

  public void polled(Instant now) { this.lastPolledAt = now; }

  public void cronFired(Instant now) { this.lastCronAt = now; }

  public UUID getId() { return id; }
  public UUID getProjectId() { return projectId; }
  public AutomationKind getKind() { return kind; }
  public boolean isEnabled() { return enabled; }
  public AutomationTrigger getTrigger() { return trigger; }
  public String getCronExpression() { return cronExpression; }
  public AutomationPostMode getPostMode() { return postMode; }
  public UUID getProviderInstanceId() { return providerInstanceId; }
  public String getModel() { return model; }
  public int getMaxModelTurns() { return maxModelTurns; }
  public Instant getLastPolledAt() { return lastPolledAt; }
  public Instant getLastCronAt() { return lastCronAt; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
}
