package tech.wenisch.petri.agent.run;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.mock;

import java.util.Set;
import org.junit.jupiter.api.Test;
import tech.wenisch.petri.audit.AuditLogService;

class TestSandboxProfileServiceTest {
  private final TestSandboxProfileService service = new TestSandboxProfileService(
      mock(TestSandboxProfileRepository.class), mock(ProjectSandboxSettingRepository.class),
      mock(AuditLogService.class));

  @Test
  void rejectsOptionLikeOrWhitespaceContainingImageReferences() {
    assertThatIllegalArgumentException().isThrownBy(() -> save("--privileged", "2", "2g"))
        .withMessageContaining("image reference");
    assertThatIllegalArgumentException().isThrownBy(() -> save("example/java:25 --privileged", "2", "2g"))
        .withMessageContaining("image reference");
  }

  @Test
  void rejectsMalformedResourceLimitsBeforeInvokingPodman() {
    assertThatIllegalArgumentException().isThrownBy(() -> save("example/java:25", "zero", "2g"))
        .withMessageContaining("positive number");
    assertThatIllegalArgumentException().isThrownBy(() -> save("example/java:25", "0", "2g"))
        .withMessageContaining("greater than zero");
    assertThatIllegalArgumentException().isThrownBy(() -> save("example/java:25", "2", "unlimited"))
        .withMessageContaining("Memory limit");
  }

  private void save(String image, String cpus, String memory) {
    service.save(null, "Profile", image, Set.of(TestBuildSystem.MAVEN), cpus, memory,
        256, 600, 2_000_000, true, true, "admin@example.test");
  }
}
