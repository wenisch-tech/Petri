package tech.wenisch.petri.agent.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.audit.AuditEventRepository;

@SpringBootTest
@Transactional
class SandboxProfileEnvironmentFlowTest {
  @Autowired TestSandboxProfileService service;
  @Autowired AuditEventRepository auditEvents;

  @Test
  void variablesAreStoredEncryptedMaskedAndKeptWhenUnchanged() {
    TestSandboxProfile profile = service.save(null, "Proxy profile", "example/java:25",
        Set.of(TestBuildSystem.MAVEN), "2", "2g", 256, 600, 2_000_000, true, true,
        "HTTPS_PROXY=http://user:hunter2@proxy:3128\nNO_PROXY=internal", "admin@petri.local");

    assertThat(profile.getEnvironmentCiphertext()).isNotBlank().doesNotContain("hunter2", "proxy");
    assertThat(service.maskedEnvironment(profile)).contains("HTTPS_PROXY=********")
        .doesNotContain("hunter2");
    assertThat(auditEvents.findTop100ByOrderByOccurredAtDesc()).noneSatisfy(
        event -> assertThat(event.getSummary()).contains("hunter2"));

    TestSandboxProfile resaved = service.save(profile.getId(), "Proxy profile", "example/java:25",
        Set.of(TestBuildSystem.MAVEN), "2", "2g", 256, 600, 2_000_000, true, true,
        "HTTPS_PROXY=********\nNO_PROXY=other", "admin@petri.local");

    assertThat(service.environment(resaved)).containsOnly(
        java.util.Map.entry("HTTPS_PROXY", "http://user:hunter2@proxy:3128"),
        java.util.Map.entry("NO_PROXY", "other"));
  }

  @Test
  void rejectsReservedNamesAndMaskedValuesForNewVariables() {
    assertThatIllegalArgumentException().isThrownBy(() -> service.save(null, "Bad", "example/java:25",
        Set.of(TestBuildSystem.MAVEN), "2", "2g", 256, 600, 2_000_000, true, true,
        "PATH=/evil", "admin@petri.local")).withMessageContaining("reserved");
    assertThatIllegalArgumentException().isThrownBy(() -> service.save(null, "Bad2", "example/java:25",
        Set.of(TestBuildSystem.MAVEN), "2", "2g", 256, 600, 2_000_000, true, true,
        "FOO=********", "admin@petri.local")).withMessageContaining("Enter a value");
  }
}
