package tech.wenisch.petri.automation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** The single row of the instance-wide automation settings. */
@Entity
@Table(name = "automation_poll_settings")
public class AutomationPollSettings {
  static final UUID ID = UUID.fromString("00000000-0000-4000-8000-000000000043");

  @Id private UUID id;

  @Column(name = "poll_interval_minutes", nullable = false)
  private int pollIntervalMinutes;

  @Column(name = "updated_at", nullable = false)
  private Instant updatedAt;

  @Column(name = "updated_by", length = 320)
  private String updatedBy;

  protected AutomationPollSettings() {}

  AutomationPollSettings(int pollIntervalMinutes, Instant now, String updatedBy) {
    this.id = ID;
    this.pollIntervalMinutes = pollIntervalMinutes;
    this.updatedAt = now;
    this.updatedBy = updatedBy;
  }

  void update(int pollIntervalMinutes, Instant now, String updatedBy) {
    this.pollIntervalMinutes = pollIntervalMinutes;
    this.updatedAt = now;
    this.updatedBy = updatedBy;
  }

  int getPollIntervalMinutes() { return pollIntervalMinutes; }
  Instant getUpdatedAt() { return updatedAt; }
  String getUpdatedBy() { return updatedBy; }
}
