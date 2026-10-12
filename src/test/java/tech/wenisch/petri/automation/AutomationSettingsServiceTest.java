package tech.wenisch.petri.automation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class AutomationSettingsServiceTest {
  @Test
  void acceptsFiveAndSixFieldCronExpressions() {
    assertThat(AutomationSettingsService.normalizeCron("0 9 * * 1-5")).isEqualTo("0 0 9 * * 1-5");
    assertThat(AutomationSettingsService.normalizeCron("0 0 9 * * 1-5")).isEqualTo("0 0 9 * * 1-5");
  }

  @Test
  void rejectsMissingOrInvalidCronExpressions() {
    assertThatIllegalArgumentException().isThrownBy(() -> AutomationSettingsService.normalizeCron(" "));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> AutomationSettingsService.normalizeCron("not a cron"))
        .withMessageContaining("Invalid cron");
  }

  @Test
  void computesTheNextFireTimeInUtc() {
    Instant next = AutomationSettingsService.nextFire(
        "0 0 9 * * *", Instant.parse("2026-05-01T10:00:00Z"));

    assertThat(next).isEqualTo(Instant.parse("2026-05-02T09:00:00Z"));
    assertThat(AutomationSettingsService.nextFire(null, Instant.now())).isNull();
    assertThat(AutomationSettingsService.nextFire("garbage", Instant.now())).isNull();
  }

  @Test
  void enablingStartsANewBaselineSoExistingPullRequestsAreNotReviewedInBulk() {
    Instant created = Instant.parse("2026-01-01T00:00:00Z");
    Instant enabledAt = Instant.parse("2026-05-01T00:00:00Z");
    ProjectAutomation automation = new ProjectAutomation(
        java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), AutomationKind.CODE_REVIEW, created);

    automation.update(true, AutomationTrigger.PULL_REQUEST, null, AutomationPostMode.AUTO,
        null, null, 20, enabledAt);

    assertThat(automation.getLastPolledAt()).isEqualTo(enabledAt);
    Instant later = enabledAt.plusSeconds(3600);
    automation.update(true, AutomationTrigger.PULL_REQUEST, null, AutomationPostMode.DRAFT,
        null, null, 20, later);
    assertThat(automation.getLastPolledAt()).isEqualTo(enabledAt);
  }
}
