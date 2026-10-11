package tech.wenisch.petri.agent.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tech.wenisch.petri.agent.run.PackageInstallService;
import tech.wenisch.petri.agent.run.PackageInstallStatus;
import tech.wenisch.petri.agent.run.PackageInstallView;
import tools.jackson.databind.ObjectMapper;

class ApprovalGateTest {
  private final PackageInstallService installs = mock(PackageInstallService.class);
  private final ApprovalGate gate = new ApprovalGate(
      null, null, installs, new ObjectMapper());

  private PackageInstallView view(UUID id, PackageInstallStatus status, String output) {
    return new PackageInstallView(id, UUID.randomUUID(), UUID.randomUUID(), List.of("jq"), "need jq",
        status, null, output, false, Instant.now(), null, null, null);
  }

  @Test
  void detectsGatedToolResultsOnly() {
    UUID id = UUID.randomUUID();
    assertThat(gate.detect("install_packages", "c1", "{\"packageInstallId\":\"" + id + "\"}"))
        .hasValueSatisfying(value -> assertThat(value.id()).isEqualTo(id));
    assertThat(gate.detect("read_file", "c2", "{\"id\":\"" + id + "\"}")).isEmpty();
    assertThat(gate.detect("install_packages", "c3", "not json")).isEmpty();
  }

  @Test
  void staysOpenWhilePendingAndExplainsDecline() {
    UUID id = UUID.randomUUID();
    ApprovalGate.Gated gated = new ApprovalGate.Gated(ApprovalGate.Kind.PACKAGES, id, "c1",
        "install_packages");

    when(installs.get(id)).thenReturn(view(id, PackageInstallStatus.PROPOSED, null));
    assertThat(gate.resolved(gated)).isEmpty();

    when(installs.get(id)).thenReturn(view(id, PackageInstallStatus.REJECTED, null));
    assertThat(gate.resolved(gated)).hasValueSatisfying(text -> assertThat(text)
        .contains("\"finalStatus\":\"REJECTED\"").contains("DECLINED"));

    when(installs.get(id)).thenReturn(view(id, PackageInstallStatus.INSTALLED, "OK"));
    assertThat(gate.resolved(gated)).hasValueSatisfying(text -> assertThat(text)
        .contains("\"finalStatus\":\"INSTALLED\"").contains("now on PATH"));
  }
}
