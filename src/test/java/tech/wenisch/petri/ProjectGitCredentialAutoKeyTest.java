package tech.wenisch.petri;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.git.GitAuthenticationType;
import tech.wenisch.petri.git.ProjectGitCredentialService;
import tech.wenisch.petri.git.SaveGitCredentialCommand;
import tech.wenisch.petri.project.CreateProjectCommand;
import tech.wenisch.petri.project.Project;
import tech.wenisch.petri.project.ProjectService;
import tech.wenisch.petri.secret.SecretCipher;

@SpringBootTest(properties = "petri.secrets.master-key=")
@Transactional
class ProjectGitCredentialAutoKeyTest {
  private static final String ADMIN_EMAIL = "test-admin@petri.local";

  @TempDir static Path temporaryDirectory;

  @DynamicPropertySource
  static void keyFileProperty(DynamicPropertyRegistry registry) {
    registry.add(
        "petri.secrets.master-key-file",
        () -> temporaryDirectory.resolve("petri-master.key").toString());
  }

  @Autowired ProjectService projectService;
  @Autowired ProjectGitCredentialService credentials;
  @Autowired SecretCipher cipher;
  @Autowired JdbcTemplate jdbc;
  @PersistenceContext EntityManager entityManager;

  @Test
  void generatedMasterKeyEncryptsPersistedCredential() {
    Project project = projectService.create(
        new CreateProjectCommand(
            "Auto-key credential " + UUID.randomUUID(),
            "https://git.example.test/platform/auto-key.git",
            "main"),
        ADMIN_EMAIL);

    credentials.save(
        project.getId(),
        new SaveGitCredentialCommand(GitAuthenticationType.TOKEN, null, "plain-secret"),
        ADMIN_EMAIL);
    entityManager.flush();

    String ciphertext = jdbc.queryForObject(
        "select secret_ciphertext from project_git_credential where project_id = ?",
        String.class,
        project.getId());
    assertThat(ciphertext).startsWith("v1:").doesNotContain("plain-secret");
    assertThat(cipher.decrypt(ciphertext)).isEqualTo("plain-secret");
    assertThat(temporaryDirectory.resolve("petri-master.key")).isRegularFile();
  }
}
