package tech.wenisch.petri.project;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProjectInitializationJobTest {
  @Test
  void onlyTheCurrentLeaseOwnerCanRenewOrFinishAJob() {
    Instant now = Instant.parse("2026-10-11T10:00:00Z");
    ProjectInitializationJob job = new ProjectInitializationJob(
        UUID.randomUUID(), UUID.randomUUID(), now);
    job.claim("worker:first", now, now.plus(15, ChronoUnit.MINUTES));

    assertThat(job.renew("worker:stale", now.plusSeconds(30), now.plusSeconds(900))).isFalse();
    assertThat(job.completed("worker:stale", now.plusSeconds(60))).isFalse();
    assertThat(job.failed("worker:stale", "stale failure", now.plusSeconds(60))).isFalse();
    assertThat(job.getStatus()).isEqualTo(ProjectInitializationStatus.RUNNING);
    assertThat(job.getErrorMessage()).isNull();

    Instant renewedUntil = now.plus(20, ChronoUnit.MINUTES);
    assertThat(job.renew("worker:first", now.plusSeconds(60), renewedUntil)).isTrue();
    assertThat(job.getLeaseUntil()).isEqualTo(renewedUntil);
    assertThat(job.completed("worker:first", now.plusSeconds(90))).isTrue();
    assertThat(job.getStatus()).isEqualTo(ProjectInitializationStatus.COMPLETED);
    assertThat(job.getLeaseUntil()).isNull();
  }
}
