package tech.wenisch.petri.agent.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tech.wenisch.petri.audit.AuditLogService;
import tech.wenisch.petri.secret.SecretCipher;

class ProjectEnvironmentTest {
  private final TestSandboxProfileRepository profiles = mock(TestSandboxProfileRepository.class);
  private final ProjectSandboxSettingRepository settings = mock(ProjectSandboxSettingRepository.class);
  private final SecretCipher cipher = mock(SecretCipher.class);
  private final TestSandboxProfileService service =
      new TestSandboxProfileService(profiles, settings, mock(AuditLogService.class), cipher);
  private final UUID projectId = UUID.randomUUID();

  private ProjectSandboxSetting settingWith(String ciphertext) {
    ProjectSandboxSetting setting = new ProjectSandboxSetting(projectId, UUID.randomUUID(), null);
    setting.environmentCiphertext(ciphertext);
    when(settings.findById(projectId)).thenReturn(Optional.of(setting));
    return setting;
  }

  @Test
  void savesEncryptedVariablesAndAuditsOnlyNames() {
    ProjectSandboxSetting setting = settingWith(null);
    when(cipher.encrypt(any())).thenAnswer(call -> "enc:" + call.getArgument(0));

    service.saveProjectEnvironment(projectId, "NPM_TOKEN=secret\nSPRING_PROFILES_ACTIVE=test", "a@p.local");

    assertThat(setting.environmentCiphertext()).startsWith("enc:").contains("NPM_TOKEN=secret");
  }

  @Test
  void anUnchangedMaskedValueKeepsTheStoredSecret() {
    ProjectSandboxSetting setting = settingWith("stored");
    when(cipher.decrypt("stored")).thenReturn("NPM_TOKEN=secret\n");
    when(cipher.encrypt(any())).thenAnswer(call -> "enc:" + call.getArgument(0));

    assertThat(service.maskedProjectEnvironment(projectId)).isEqualTo("NPM_TOKEN=********\n");
    service.saveProjectEnvironment(projectId, "NPM_TOKEN=********\nEXTRA=1", "a@p.local");

    assertThat(setting.environmentCiphertext()).contains("NPM_TOKEN=secret").contains("EXTRA=1");
  }

  @Test
  void aNewVariableCannotBeSavedWithTheMask() {
    settingWith(null);

    assertThatIllegalArgumentException()
        .isThrownBy(() -> service.saveProjectEnvironment(projectId, "NEW=********", "a@p.local"))
        .withMessageContaining("Enter a value");
  }

  @Test
  void projectVariablesOverrideTheProfileOnAClash() {
    TestSandboxProfile profile = new TestSandboxProfile(UUID.randomUUID(), "p", "img:1",
        java.util.EnumSet.of(TestBuildSystem.MAVEN), "2", "2g", 256, 60, 4096, false, true,
        "profileCipher", java.time.Instant.now());
    ProjectSandboxSetting setting = new ProjectSandboxSetting(projectId, profile.getId(), null);
    setting.environmentCiphertext("projectCipher");
    when(settings.findById(projectId)).thenReturn(Optional.of(setting));
    when(profiles.findById(profile.getId())).thenReturn(Optional.of(profile));
    when(cipher.decrypt("profileCipher")).thenReturn("HTTPS_PROXY=http://proxy\nLEVEL=profile\n");
    when(cipher.decrypt("projectCipher")).thenReturn("LEVEL=project\n");

    assertThat(service.environmentFor(projectId))
        .containsEntry("HTTPS_PROXY", "http://proxy")
        .containsEntry("LEVEL", "project");
    assertThat(Set.copyOf(service.inheritedVariableNames(projectId)))
        .containsExactlyInAnyOrder("HTTPS_PROXY", "LEVEL");
  }
}
