package tech.wenisch.petri.agent.orchestration;

import static org.hamcrest.Matchers.containsString;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.agent.model.ModelProvider;
import tech.wenisch.petri.project.Project;
import tech.wenisch.petri.project.ProjectRepository;
import tech.wenisch.petri.security.AppUserRepository;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ProjectRunsPageRenderTest {
  private static final String ADMIN = "test-admin@petri.local";

  @Autowired MockMvc mockMvc;
  @Autowired AgentRunRepository runs;
  @Autowired AgentChatRepository chats;
  @Autowired ProjectRepository projects;
  @Autowired AppUserRepository users;
  @Autowired JdbcTemplate jdbc;

  @Test
  void runsAreOneListWithATypeColumnAndFiltersAbove() throws Exception {
    UUID userId = users.findByEmailIgnoreCase(ADMIN).orElseThrow().getId();
    UUID projectId = UUID.randomUUID();
    projects.saveAndFlush(new Project(projectId, "Render project", "render-" + projectId,
        "https://example.test/acme/repo.git", "main", userId, Instant.now()));
    UUID worktreeId = UUID.randomUUID();
    jdbc.update("insert into project_worktree (id, project_id, git_revision, relative_path, status,"
            + " created_by, created_at) values (?, ?, 'main', ?, 'ACTIVE', ?, current_timestamp)",
        worktreeId, projectId, "runs/" + worktreeId, userId);
    Instant now = Instant.now();
    AgentChat chat = chats.save(new AgentChat(UUID.randomUUID(), worktreeId, "Fix the bug", userId, now));
    UUID runId = runs.save(new AgentRun(UUID.randomUUID(), chat.id(), 0, worktreeId,
        ModelProvider.OLLAMA, "llama-test", "Fix the bug in the parser", 5, 1024, 32768, null, null,
        userId, now)).id();

    // Workflow stage prompts can be huge; the page must neither fail nor ship them whole.
    AgentChat large = chats.save(new AgentChat(UUID.randomUUID(), worktreeId, "Large", userId, now));
    runs.save(new AgentRun(UUID.randomUUID(), large.id(), 0, worktreeId, ModelProvider.OLLAMA,
        "llama-test", "BEGIN-" + "x".repeat(150_000) + "-END", 5, 1024, 32768, null, null,
        userId, now));

    mockMvc.perform(get("/projects/{id}/runs", projectId).param("embedded", "true")
            .with(user(ADMIN).roles("INSTANCE_ADMIN")))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Fix the bug in the parser")))
        .andExpect(content().string(containsString("data-kind=\"chat\"")))
        .andExpect(content().string(containsString("runs-filter-type")))
        .andExpect(content().string(containsString("runs-filter-status")))
        .andExpect(content().string(containsString("Workflow stage")))
        .andExpect(content().string(containsString("BEGIN-")))
        .andExpect(content().string(org.hamcrest.Matchers.not(containsString("-END"))));

    // A run opens inside the Runs tab: content only, with its back link to the list.
    mockMvc.perform(get("/runs/{id}", runId).param("embedded", "true")
            .with(user(ADMIN).roles("INSTANCE_ADMIN")))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Fix the bug in the parser")))
        .andExpect(content().string(org.hamcrest.Matchers.not(containsString("<html"))));
    mockMvc.perform(get("/runs/{id}", runId).with(user(ADMIN).roles("INSTANCE_ADMIN")))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("<html")));
  }
}
