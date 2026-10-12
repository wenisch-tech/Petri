package tech.wenisch.petri.agent.orchestration;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.agent.model.ModelProvider;
import tech.wenisch.petri.project.Project;
import tech.wenisch.petri.project.ProjectRepository;
import tech.wenisch.petri.security.AppUserRepository;

@SpringBootTest
@Transactional
class ProjectRunListingTest {
  @Autowired AgentRunService service;
  @Autowired AgentRunRepository runs;
  @Autowired AgentChatRepository chats;
  @Autowired ProjectRepository projects;
  @Autowired AppUserRepository users;
  @Autowired JdbcTemplate jdbc;

  private UUID userId() {
    return users.findByEmailIgnoreCase("test-admin@petri.local").orElseThrow().getId();
  }

  private UUID project(String name) {
    UUID id = UUID.randomUUID();
    return projects.saveAndFlush(new Project(id, name, name + "-" + id,
        "https://example.test/acme/repo.git", "main", userId(), Instant.now())).getId();
  }

  /** A worktree of the project holding one run, optionally part of a workflow. */
  private UUID runOn(UUID projectId, String prompt, String removedStatus) {
    UUID worktreeId = UUID.randomUUID();
    jdbc.update("insert into project_worktree (id, project_id, git_revision, relative_path, status,"
            + " created_by, created_at) values (?, ?, 'main', ?, ?, ?, current_timestamp)",
        worktreeId, projectId, "runs/" + worktreeId, removedStatus, userId());
    Instant now = Instant.now();
    AgentChat chat = chats.save(new AgentChat(UUID.randomUUID(), worktreeId, prompt, userId(), now));
    AgentRun run = runs.save(new AgentRun(UUID.randomUUID(), chat.id(), 0, worktreeId,
        ModelProvider.OLLAMA, "m", prompt, 5, 1024, 32768, null, null, userId(), now));
    return run.id();
  }

  @Test
  void onlyRunsOnWorktreesOfTheProjectAreListedIncludingRemovedWorktrees() {
    UUID mine = project("mine");
    UUID other = project("other");
    UUID active = runOn(mine, "active worktree run", "ACTIVE");
    UUID removed = runOn(mine, "removed worktree run", "REMOVED");
    UUID foreign = runOn(other, "other project run", "ACTIVE");

    var listed = service.listForProject(mine, userId(), false);

    assertThat(listed).extracting(AgentRunView::id).containsExactlyInAnyOrder(active, removed);
    assertThat(listed).extracting(AgentRunView::id).doesNotContain(foreign);
    assertThat(service.listForProject(other, userId(), false))
        .extracting(AgentRunView::id).containsExactly(foreign);
    assertThat(service.listForProject(project("empty"), userId(), true)).isEmpty();
  }

  @Test
  void runsOfOtherUsersAreNotListed() {
    UUID mine = project("mine2");
    runOn(mine, "mine", "ACTIVE");

    assertThat(service.listForProject(mine, UUID.randomUUID(), true)).isEmpty();
  }
}
