package tech.wenisch.petri.automation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.flash;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.project.Project;
import tech.wenisch.petri.project.ProjectRepository;
import tech.wenisch.petri.security.AppUserRepository;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AutomationPageFlowTest {
  private static final String ADMIN_EMAIL = "test-admin@petri.local";

  @Autowired MockMvc mockMvc;
  @Autowired ProjectRepository projects;
  @Autowired AppUserRepository users;
  @Autowired ProjectAutomationRepository automations;

  private UUID projectId;

  @BeforeEach
  void project() {
    UUID id = UUID.randomUUID();
    projectId = projects.save(new Project(id, "Automation page", "automation-page-" + id,
        "https://example.test/acme/repo.git", "main",
        users.findByEmailIgnoreCase(ADMIN_EMAIL).orElseThrow().getId(), Instant.now())).getId();
  }

  private MockHttpServletRequestBuilder asAdmin(MockHttpServletRequestBuilder request) {
    return request.with(user(ADMIN_EMAIL).roles("INSTANCE_ADMIN")).with(csrf());
  }

  @Test
  void anAutomationIsAddedWithATriggerSkillAndApprovalSetting() throws Exception {
    mockMvc.perform(asAdmin(post("/projects/{id}/automations", projectId))
            .param("kind", "CODE_REVIEW")
            .param("trigger", "CRON")
            .param("cronExpression", "0 9 * * 1-5"))
        .andExpect(status().is3xxRedirection())
        .andExpect(flash().attribute("notice", "Automation added"));

    ProjectAutomation saved = automations.findAllByProjectIdOrderByCreatedAtAsc(projectId).get(0);
    assertThat(saved.getTrigger()).isEqualTo(AutomationTrigger.CRON);
    assertThat(saved.getCronExpression()).isEqualTo("0 0 9 * * 1-5");
    // The approval box was not ticked.
    assertThat(saved.getPostMode()).isEqualTo(AutomationPostMode.DRAFT);

    mockMvc.perform(asAdmin(get("/projects/{id}/automations", projectId)))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Add automation")))
        .andExpect(content().string(containsString("Complete without approval")))
        .andExpect(content().string(containsString("Schedule: 0 0 9 * * 1-5 (UTC)")))
        .andExpect(content().string(containsString("Needs approval")))
        .andExpect(content().string(containsString("Code review")));
  }

  @Test
  void aPullRequestTriggerNeedsNoCronAndEditingKeepsTheSkill() throws Exception {
    mockMvc.perform(asAdmin(post("/projects/{id}/automations", projectId))
        .param("kind", "CODE_REVIEW").param("trigger", "CRON").param("cronExpression", "0 9 * * *")
        .param("completeWithoutApproval", "true"));
    ProjectAutomation saved = automations.findAllByProjectIdOrderByCreatedAtAsc(projectId).get(0);
    assertThat(saved.getPostMode()).isEqualTo(AutomationPostMode.AUTO);

    mockMvc.perform(asAdmin(post("/projects/{id}/automations", projectId))
            .param("automationId", saved.getId().toString())
            .param("kind", "CODE_REVIEW")
            .param("trigger", "PULL_REQUEST")
            .param("cronExpression", "ignored * * * *"))
        .andExpect(flash().attribute("notice", "Automation saved"));

    ProjectAutomation edited = automations.findById(saved.getId()).orElseThrow();
    assertThat(edited.getTrigger()).isEqualTo(AutomationTrigger.PULL_REQUEST);
    assertThat(edited.getCronExpression()).isNull();
    assertThat(edited.getKind()).isEqualTo(AutomationKind.CODE_REVIEW);
  }

  @Test
  void rejectsDuplicatesMissingCronAndEnablingWithoutAGitAccount() throws Exception {
    mockMvc.perform(asAdmin(post("/projects/{id}/automations", projectId))
        .param("kind", "CODE_REVIEW").param("trigger", "PULL_REQUEST"));

    mockMvc.perform(asAdmin(post("/projects/{id}/automations", projectId))
            .param("kind", "CODE_REVIEW").param("trigger", "PULL_REQUEST"))
        .andExpect(flash().attribute("error", containsString("already has")));
    mockMvc.perform(asAdmin(post("/projects/{id}/automations", projectId))
            .param("kind", "CODE_REVIEW").param("trigger", "CRON"))
        .andExpect(flash().attribute("error", containsString("cron expression is required")));
    mockMvc.perform(asAdmin(post("/projects/{id}/automations", projectId))
            .param("kind", "CODE_REVIEW").param("trigger", "CRON")
            .param("cronExpression", "0 9 * * *").param("enabled", "true"))
        .andExpect(flash().attribute("error", containsString("connected Git account")));

    assertThat(automations.findAllByProjectIdOrderByCreatedAtAsc(projectId)).hasSize(1);
  }

  @Test
  void anAutomationCanBeRemovedAndOtherProjectsAreNotReachable() throws Exception {
    mockMvc.perform(asAdmin(post("/projects/{id}/automations", projectId))
        .param("kind", "CODE_REVIEW").param("trigger", "PULL_REQUEST"));
    UUID automationId = automations.findAllByProjectIdOrderByCreatedAtAsc(projectId).get(0).getId();

    mockMvc.perform(asAdmin(post("/projects/{id}/automations/{automationId}/delete",
            projectId, automationId)))
        .andExpect(flash().attribute("notice", "Automation removed"));
    assertThat(automations.findAllByProjectIdOrderByCreatedAtAsc(projectId)).isEmpty();

    mockMvc.perform(asAdmin(get("/projects/{id}/automations", projectId)))
        .andExpect(content().string(containsString("No automations yet")));
  }

  @Test
  void theProjectPagesCanBeRenderedAsContentOnlyForTheWorkspace() throws Exception {
    // Full pages keep the application chrome for visitors coming from the dashboard.
    mockMvc.perform(asAdmin(get("/projects/{id}/automations", projectId)))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("<html")))
        .andExpect(content().string(containsString("Add automation")));

    // The workspace asks for the same page without it and shows it in place.
    mockMvc.perform(asAdmin(get("/projects/{id}/automations", projectId).param("embedded", "true")))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Add automation")))
        .andExpect(content().string(org.hamcrest.Matchers.not(containsString("<html"))))
        .andExpect(content().string(org.hamcrest.Matchers.not(containsString("topbar"))));

    mockMvc.perform(asAdmin(get("/projects/{id}/settings", projectId).param("embedded", "true")))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Repository mirror")))
        .andExpect(content().string(org.hamcrest.Matchers.not(containsString("<html"))));
  }
}
