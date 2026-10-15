package tech.wenisch.petri.automation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.project.Project;
import tech.wenisch.petri.project.ProjectRepository;
import tech.wenisch.petri.security.AppUser;
import tech.wenisch.petri.security.AppUserRepository;

@SpringBootTest(properties =
    "petri.secrets.master-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=")
@Transactional
class AutomationActionServiceTest {
  @Autowired AutomationActionService service;
  @Autowired AutomationActionRepository actions;
  @Autowired ProjectAutomationRepository automations;
  @Autowired ProjectRepository projects;
  @Autowired AppUserRepository users;

  @AfterEach
  void resetCatalog() {
    AutomationKind.install(List.of());
  }

  @Test
  void theBuiltInActionsAreWrittenToTheTable() {
    service.seed();

    assertThat(actions.findAll()).extracting(AutomationAction::getId).contains(
        "CODE_REVIEW", "CODE_REVIEW_AND_IMPROVE", "VULNERABILITY_SCAN", "VULNERABILITY_FIX_AND_MERGE");
    assertThat(actions.findById("VULNERABILITY_FIX_PR").orElseThrow().toKind().branchBased()).isTrue();
  }

  @Test
  void aCustomActionIsOnlyARowAndAutomationsCanUseIt() {
    AutomationKind custom = new AutomationKind("DEPENDENCY_REPORT", "Dependency report",
        "Reports outdated dependencies on a schedule.", "dependency-report", "dependency-report",
        false, false, false, false, false, Set.of(AutomationTrigger.CRON), false);
    actions.save(new AutomationAction(custom, Instant.now()));
    service.seed();

    AutomationKind loaded = AutomationKind.valueOf("DEPENDENCY_REPORT");
    assertThat(loaded.title()).isEqualTo("Dependency report");
    assertThat(loaded.triggers()).containsExactly(AutomationTrigger.CRON);
    assertThat(AutomationKind.values()).contains(loaded);

    AppUser owner = users.save(new AppUser(UUID.randomUUID(), "actions@petri.local", "x", "USER"));
    Project project = projects.save(new Project(UUID.randomUUID(), "Demo", "demo",
        "https://example.test/acme/demo.git", "main", owner.getId(), Instant.now()));
    automations.saveAndFlush(new ProjectAutomation(UUID.randomUUID(), project.getId(), loaded, Instant.now()));
    ProjectAutomation back = automations.findAllByProjectIdOrderByCreatedAtAsc(project.getId()).get(0);
    assertThat(back.getKind()).isEqualTo(loaded);
    assertThat(back.getKind().skill()).isEqualTo("dependency-report");
  }

  @Test
  void anActionThatWasDeletedStillShowsInTheHistory() {
    assertThatIllegalArgumentException().isThrownBy(() -> AutomationKind.valueOf("GONE_ACTION"));

    AutomationKind placeholder = new AutomationKindConverter().convertToEntityAttribute("GONE_ACTION");

    assertThat(placeholder.id()).isEqualTo("GONE_ACTION");
    assertThat(placeholder.comments()).isFalse();
    assertThat(placeholder.improves()).isFalse();
    assertThat(placeholder.branchBased()).isFalse();
  }

  @Test
  void aBuiltInRowIsKeptCurrentButACustomOneIsNeverOverwritten() {
    AutomationKind custom = new AutomationKind("MY_ACTION", "Mine", "Mine.", "mine", "mine",
        true, false, false, false, false, Set.of(AutomationTrigger.PULL_REQUEST), false);
    actions.save(new AutomationAction(custom, Instant.now()));
    AutomationAction stale = actions.findById("CODE_REVIEW").orElse(null);
    service.seed();
    service.seed();

    assertThat(actions.findById("MY_ACTION").orElseThrow().toKind().title()).isEqualTo("Mine");
    assertThat(actions.findById("CODE_REVIEW").orElseThrow().toKind().title()).isEqualTo("Code review");
    assertThat(stale == null || stale.isBuiltin()).isTrue();
  }

  @Test
  void aCustomActionCanBeAddedChangedAndRemovedButBuiltInsAreProtected() {
    service.seed();
    var input = new AutomationActionService.CustomAction(
        "Weekly security sweep", "Scans main weekly", "vulnerability-scan", true, false, false,
        false, true);

    AutomationAction created = service.saveCustom(null, input, "admin@petri.local");

    assertThat(created.getId()).isEqualTo("WEEKLY_SECURITY_SWEEP");
    AutomationKind kind = created.toKind();
    assertThat(kind.branchBased()).isTrue();
    assertThat(kind.merges()).isTrue();
    assertThat(kind.vulnerability()).isTrue();
    assertThat(kind.builtin()).isFalse();
    assertThat(AutomationKind.valueOf("WEEKLY_SECURITY_SWEEP").title()).isEqualTo("Weekly security sweep");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> service.saveCustom(null, input, "admin@petri.local"))
        .withMessageContaining("already exists");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> service.deleteCustom("CODE_REVIEW", "admin@petri.local"))
        .withMessageContaining("Built-in");
    assertThatIllegalArgumentException()
        .isThrownBy(() -> service.saveCustom(null, new AutomationActionService.CustomAction(
            "Nope", "", "no-such-skill", false, false, false, false, false), "a"))
        .withMessageContaining("does not exist");

    service.deleteCustom("WEEKLY_SECURITY_SWEEP", "admin@petri.local");
    assertThat(actions.existsById("WEEKLY_SECURITY_SWEEP")).isFalse();
  }
}
