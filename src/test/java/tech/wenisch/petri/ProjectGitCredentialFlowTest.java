package tech.wenisch.petri;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.audit.AuditEventRepository;
import tech.wenisch.petri.project.CreateProjectCommand;
import tech.wenisch.petri.project.Project;
import tech.wenisch.petri.project.ProjectService;
import tech.wenisch.petri.secret.SecretCipher;

@SpringBootTest(properties =
    "petri.secrets.master-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=")
@AutoConfigureMockMvc
@Transactional
class ProjectGitCredentialFlowTest {
  private static final String ADMIN_EMAIL = "test-admin@petri.local";

  @Autowired MockMvc mockMvc;
  @Autowired ProjectService projectService;
  @Autowired AuditEventRepository auditEvents;
  @Autowired JdbcTemplate jdbc;
  @Autowired SecretCipher cipher;
  @PersistenceContext EntityManager entityManager;

  @Test
  void administratorCanCreateReplaceAndRemoveEncryptedCredential() throws Exception {
    Project project = project();

    mockMvc.perform(post("/projects/{id}/credential", project.getId())
            .with(user(ADMIN_EMAIL).roles("INSTANCE_ADMIN"))
            .with(csrf())
            .param("authenticationType", "TOKEN")
            .param("username", "git-user")
            .param("secret", "first-super-secret"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/projects/" + project.getId() + "/settings"));

    String stored = storedSecret(project.getId());
    assertThat(stored).startsWith("v1:").doesNotContain("first-super-secret");
    assertThat(cipher.decrypt(stored)).isEqualTo("first-super-secret");
    assertThat(rowCount(project.getId())).isOne();
    assertThat(auditActions()).contains("GIT_CREDENTIAL_CREATED");
    assertThat(auditEvents.findTop100ByOrderByOccurredAtDesc())
        .allSatisfy(event -> assertThat(event.getSummary()).doesNotContain("first-super-secret"));

    mockMvc.perform(get("/projects/{id}/settings", project.getId())
            .with(user(ADMIN_EMAIL).roles("INSTANCE_ADMIN")))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("git-user")))
        .andExpect(content().string(org.hamcrest.Matchers.not(
            org.hamcrest.Matchers.containsString("first-super-secret"))));

    mockMvc.perform(post("/projects/{id}/credential", project.getId())
            .with(user(ADMIN_EMAIL).roles("INSTANCE_ADMIN"))
            .with(csrf())
            .param("authenticationType", "USERNAME_PASSWORD")
            .param("username", "deploy")
            .param("secret", "replacement-secret"))
        .andExpect(status().is3xxRedirection());

    assertThat(rowCount(project.getId())).isOne();
    assertThat(cipher.decrypt(storedSecret(project.getId()))).isEqualTo("replacement-secret");
    assertThat(auditActions()).contains("GIT_CREDENTIAL_REPLACED");

    mockMvc.perform(post("/projects/{id}/credential/delete", project.getId())
            .with(user(ADMIN_EMAIL).roles("INSTANCE_ADMIN"))
            .with(csrf()))
        .andExpect(status().is3xxRedirection());

    assertThat(rowCount(project.getId())).isZero();
    assertThat(auditActions()).contains("GIT_CREDENTIAL_REMOVED");
  }

  @Test
  void regularUserCannotReadOrChangeGitCredentialSettings() throws Exception {
    Project project = project();

    mockMvc.perform(get("/projects/{id}/settings", project.getId())
            .with(user("member@petri.local").roles("USER")))
        .andExpect(status().isForbidden());
    mockMvc.perform(post("/projects/{id}/credential", project.getId())
            .with(user("member@petri.local").roles("USER"))
            .with(csrf())
            .param("authenticationType", "TOKEN")
            .param("secret", "must-not-be-stored"))
        .andExpect(status().isForbidden());
    mockMvc.perform(post("/projects/{id}/credential/delete", project.getId())
            .with(user("member@petri.local").roles("USER"))
            .with(csrf()))
        .andExpect(status().isForbidden());
    mockMvc.perform(post("/projects/{id}/mirror/sync", project.getId())
            .with(user("member@petri.local").roles("USER"))
            .with(csrf()))
        .andExpect(status().isForbidden());

    assertThat(rowCount(project.getId())).isZero();
  }

  @Test
  void usernamePasswordAuthenticationRequiresAUsername() throws Exception {
    Project project = project();

    mockMvc.perform(post("/projects/{id}/credential", project.getId())
            .with(user(ADMIN_EMAIL).roles("INSTANCE_ADMIN"))
            .with(csrf())
            .param("authenticationType", "USERNAME_PASSWORD")
            .param("secret", "must-not-be-stored"))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString(
            "Username is required for username/password authentication")))
        .andExpect(content().string(org.hamcrest.Matchers.not(
            org.hamcrest.Matchers.containsString("must-not-be-stored"))));

    assertThat(rowCount(project.getId())).isZero();
  }

  private Project project() {
    return projectService.create(
        new CreateProjectCommand(
            "Credential test " + UUID.randomUUID(),
            "https://git.example.test/platform/repository.git",
            "main"),
        ADMIN_EMAIL);
  }

  private String storedSecret(UUID projectId) {
    entityManager.flush();
    return jdbc.queryForObject(
        "select secret_ciphertext from project_git_credential where project_id = ?",
        String.class,
        projectId);
  }

  private int rowCount(UUID projectId) {
    entityManager.flush();
    return jdbc.queryForObject(
        "select count(*) from project_git_credential where project_id = ?", Integer.class, projectId);
  }

  private java.util.List<String> auditActions() {
    return auditEvents.findTop100ByOrderByOccurredAtDesc().stream()
        .map(event -> event.getAction())
        .toList();
  }
}
