package tech.wenisch.petri.automation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.agent.orchestration.AgentRunService;
import tech.wenisch.petri.project.Project;
import tech.wenisch.petri.project.ProjectRepository;
import tech.wenisch.petri.security.AppUserRepository;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ProjectRunsPageFlowTest {
  private static final String ADMIN = "test-admin@petri.local";

  @Autowired MockMvc mockMvc;
  @Autowired ProjectRepository projects;
  @Autowired AppUserRepository users;
  @Autowired AgentRunService runs;

  private UUID project(String name) {
    UUID id = UUID.randomUUID();
    return projects.save(new Project(id, name, name.toLowerCase().replace(' ', '-') + "-" + id,
        "https://example.test/acme/repo.git", "main",
        users.findByEmailIgnoreCase(ADMIN).orElseThrow().getId(), Instant.now())).getId();
  }

  @Test
  void theRunsPageIsScopedToTheProjectAndHasAContentOnlyMode() throws Exception {
    UUID mine = project("Runs project");
    UUID other = project("Other project");
    UUID adminId = users.findByEmailIgnoreCase(ADMIN).orElseThrow().getId();

    // No worktrees, so no runs exist for either project: the page is empty rather than global.
    assertThat(runs.listForProject(mine, adminId, true)).isEmpty();
    assertThat(runs.listForProject(other, adminId, true)).isEmpty();

    mockMvc.perform(get("/projects/{id}/runs", mine).with(user(ADMIN).roles("INSTANCE_ADMIN")))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Runs project")))
        .andExpect(content().string(containsString("No runs yet")))
        .andExpect(content().string(containsString("<html")));

    mockMvc.perform(get("/projects/{id}/runs", mine).param("embedded", "true")
            .with(user(ADMIN).roles("INSTANCE_ADMIN")))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("No runs yet")))
        .andExpect(content().string(not(containsString("<html"))));
  }

  @Test
  void aProjectOfAnotherUserIsNotReachable() throws Exception {
    mockMvc.perform(get("/projects/{id}/runs", UUID.randomUUID())
            .with(user(ADMIN).roles("INSTANCE_ADMIN")))
        .andExpect(status().is4xxClientError());
  }
}
