package tech.wenisch.petri.automation;

import java.time.Duration;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.audit.AuditLogService;

/**
 * How often pull requests are checked for automations. The default comes from
 * {@code petri.automation.poll-interval-ms}; an administrator overrides it under Administration.
 */
@Service
public class AutomationPollSettingsService {
  public static final int MIN_MINUTES = 1;
  public static final int MAX_MINUTES = 1440;

  /** What the settings page shows. {@code updatedAt} is null while the default is in use. */
  public record View(int intervalMinutes, int defaultMinutes, Instant updatedAt, String updatedBy) {}

  private final AutomationPollSettingsRepository settings;
  private final AuditLogService audit;
  private final int defaultMinutes;

  AutomationPollSettingsService(
      AutomationPollSettingsRepository settings, AuditLogService audit,
      @Value("${petri.automation.poll-interval-ms:120000}") long defaultIntervalMs) {
    this.settings = settings;
    this.audit = audit;
    long minutes = (defaultIntervalMs + 59_999) / 60_000;
    this.defaultMinutes = (int) Math.max(MIN_MINUTES, Math.min(MAX_MINUTES, minutes));
  }

  @Transactional(readOnly = true)
  public Duration interval() {
    return Duration.ofMinutes(view().intervalMinutes());
  }

  @Transactional(readOnly = true)
  public View view() {
    return settings.findById(AutomationPollSettings.ID)
        .map(row -> new View(row.getPollIntervalMinutes(), defaultMinutes, row.getUpdatedAt(), row.getUpdatedBy()))
        .orElse(new View(defaultMinutes, defaultMinutes, null, null));
  }

  @Transactional
  public void save(int minutes, String actorEmail) {
    if (minutes < MIN_MINUTES || minutes > MAX_MINUTES) {
      throw new IllegalArgumentException(
          "The interval must be between " + MIN_MINUTES + " and " + MAX_MINUTES + " minutes");
    }
    Instant now = Instant.now();
    AutomationPollSettings row = settings.findById(AutomationPollSettings.ID).orElse(null);
    if (row == null) row = new AutomationPollSettings(minutes, now, actorEmail);
    else row.update(minutes, now, actorEmail);
    settings.save(row);
    audit.record(actorEmail, "AUTOMATION_POLL_INTERVAL_CHANGED", "AUTOMATION_SETTINGS",
        AutomationPollSettings.ID, minutes + " minutes");
  }
}
