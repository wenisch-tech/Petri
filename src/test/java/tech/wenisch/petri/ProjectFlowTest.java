package tech.wenisch.petri;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.audit.AuditEventRepository;
import tech.wenisch.petri.project.CreateProjectCommand;
import tech.wenisch.petri.project.Project;
import tech.wenisch.petri.project.ProjectRepository;
import tech.wenisch.petri.project.ProjectService;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ProjectFlowTest {
  private static final String ADMIN_EMAIL = "test-admin@petri.local";

  @Autowired MockMvc mockMvc;
  @Autowired ProjectRepository projects;
  @Autowired ProjectService projectService;
  @Autowired AuditEventRepository auditEvents;

  @Test
  void administratorCanCreateAProjectAndAuditEvent() throws Exception {
    mockMvc.perform(post("/projects")
            .with(user(ADMIN_EMAIL).roles("INSTANCE_ADMIN"))
            .with(csrf())
            .param("name", "Inventory API")
            .param("repositoryUri", "https://git.example.test/platform/inventory.git")
            .param("defaultBranch", "develop"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/projects"));

    Project project = projects.findBySlug("inventory-api").orElseThrow();
    assertThat(project.getDefaultBranch()).isEqualTo("develop");
    assertThat(auditEvents.findTop100ByOrderByOccurredAtDesc())
        .anySatisfy(event -> {
          assertThat(event.getAction()).isEqualTo("PROJECT_CREATED");
          assertThat(event.getTargetId()).isEqualTo(project.getId());
          assertThat(event.getActorEmail()).isEqualTo(ADMIN_EMAIL);
        });
  }

  @Test
  void projectAndAuditPagesRenderForAnAdministrator() throws Exception {
    mockMvc.perform(get("/projects").with(user(ADMIN_EMAIL).roles("INSTANCE_ADMIN")))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Projects")));

    mockMvc.perform(get("/audit").with(user(ADMIN_EMAIL).roles("INSTANCE_ADMIN")))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Audit log")));
  }

  @Test
  void regularUserCannotCreateProjectsOrReadAuditLog() throws Exception {
    mockMvc.perform(post("/projects")
            .with(user("member@petri.local").roles("USER"))
            .with(csrf())
            .param("name", "Forbidden")
            .param("repositoryUri", "https://git.example.test/forbidden.git"))
        .andExpect(status().isForbidden());

    mockMvc.perform(get("/audit").with(user("member@petri.local").roles("USER")))
        .andExpect(status().isForbidden());
  }

  @Test
  void duplicateProjectNamesReceiveStableUniqueSlugs() {
    Project first = projectService.create(
        new CreateProjectCommand("Orders", "https://git.example.test/orders-one.git", "main"),
        ADMIN_EMAIL);
    Project second = projectService.create(
        new CreateProjectCommand("Orders", "https://git.example.test/orders-two.git", "main"),
        ADMIN_EMAIL);

    assertThat(first.getSlug()).isEqualTo("orders");
    assertThat(second.getSlug()).isEqualTo("orders-2");
  }
}
