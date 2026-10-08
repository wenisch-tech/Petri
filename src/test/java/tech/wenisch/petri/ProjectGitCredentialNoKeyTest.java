package tech.wenisch.petri;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.git.GitAuthenticationType;
import tech.wenisch.petri.git.ProjectGitCredentialService;
import tech.wenisch.petri.git.SaveGitCredentialCommand;
import tech.wenisch.petri.project.CreateProjectCommand;
import tech.wenisch.petri.project.Project;
import tech.wenisch.petri.project.ProjectService;

@SpringBootTest
@Transactional
class ProjectGitCredentialNoKeyTest {
  private static final String ADMIN_EMAIL = "test-admin@petri.local";

  @Autowired ProjectService projectService;
  @Autowired ProjectGitCredentialService credentials;
  @Autowired JdbcTemplate jdbc;

  @Test
  void refusesToPersistCredentialWithoutMasterKey() {
    Project project = projectService.create(
        new CreateProjectCommand(
            "No-key credential " + UUID.randomUUID(),
            "https://git.example.test/platform/no-key.git",
            "main"),
        ADMIN_EMAIL);

    assertThatIllegalStateException().isThrownBy(() -> credentials.save(
        project.getId(),
        new SaveGitCredentialCommand(GitAuthenticationType.TOKEN, null, "plain-secret"),
        ADMIN_EMAIL));

    Integer count = jdbc.queryForObject(
        "select count(*) from project_git_credential where project_id = ?",
        Integer.class,
        project.getId());
    assertThat(count).isZero();
  }
}
