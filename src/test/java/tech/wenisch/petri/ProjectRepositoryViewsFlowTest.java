package tech.wenisch.petri;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.git.provider.PipelineRun;
import tech.wenisch.petri.git.provider.ProjectRepositoryActivityService;
import tech.wenisch.petri.git.provider.PullRequestSummary;
import tech.wenisch.petri.project.Project;
import tech.wenisch.petri.project.ProjectRepository;
import tech.wenisch.petri.security.AppUser;
import tech.wenisch.petri.security.AppUserRepository;

@SpringBootTest(properties =
    "petri.secrets.master-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=")
@AutoConfigureMockMvc
@Transactional
class ProjectRepositoryViewsFlowTest {
  @Autowired MockMvc mockMvc;
  @Autowired AppUserRepository users;
  @Autowired ProjectRepository projects;
  @MockitoBean ProjectRepositoryActivityService activity;

  @Test
  void pullRequestAndPipelineViewsRenderStatesAndAreOwnerScoped() throws Exception {
    AppUser alice = users.save(new AppUser(UUID.randomUUID(), "alice@petri.local", "unused", "USER"));
    users.save(new AppUser(UUID.randomUUID(), "bob@petri.local", "unused", "USER"));
    Project project = projects.save(new Project(UUID.randomUUID(), "Demo", "demo",
        "https://example.test/acme/demo.git", "main", alice.getId(), Instant.now()));
    var repository = new ProjectRepositoryActivityService.Repository(
        "FORGEJO", "acme/demo", "https://example.test/acme/demo");
    when(activity.pullRequests(project.getId(), "alice@petri.local", "open"))
        .thenReturn(new ProjectRepositoryActivityService.PullRequests(repository, "open",
            List.of(new PullRequestSummary(7, "Add feature", "carol", "feature", "main",
                PullRequestSummary.State.OPEN, true, "https://example.test/pr/7",
                Instant.now(), Instant.now(), "FAILED"),
            new PullRequestSummary(8, "Fix bug", "dave", "fix", "main",
                PullRequestSummary.State.OPEN, false, "https://example.test/pr/8",
                Instant.now(), Instant.now(), "SUCCESS"),
            new PullRequestSummary(9, "Tweak", "erin", "tweak", "main",
                PullRequestSummary.State.OPEN, false, "https://example.test/pr/9",
                Instant.now(), Instant.now(), "RUNNING"),
            new PullRequestSummary(10, "Old", "frank", "old", "main",
                PullRequestSummary.State.OPEN, false, "https://example.test/pr/10",
                Instant.now(), Instant.now(), "CANCELLED")), 4, 0, 0, null, null));
    when(activity.pipelines(project.getId(), "alice@petri.local"))
        .thenReturn(new ProjectRepositoryActivityService.Pipelines(repository,
            List.of(new PipelineRun("1", "CI", "FAILED", "failure", "main", "abcdef1234", "push",
                "https://example.test/runs/1", Instant.now(), Instant.now())
                .withPullRequest(7, "Add feature")), 0, 1, 0, null, null));
    var summary = new PullRequestSummary(7, "Add feature", "carol", "feature", "main",
        PullRequestSummary.State.OPEN, false, "https://example.test/pr/7", Instant.now(), Instant.now());
    when(activity.pullRequest(project.getId(), "alice@petri.local", 7))
        .thenReturn(new ProjectRepositoryActivityService.PullRequestPage(repository,
            new tech.wenisch.petri.git.provider.PullRequestDetail(summary, "## Summary\n\nAdds **feature** with `code`.\n\n- one\n- two\n\n```java\nint x = 1;\n```\n\n| a | b |\n|---|---|\n| 1 | 2 |", "abc", 120, 31, 4,
                List.of(new tech.wenisch.petri.git.provider.PullRequestDetail.ChangedFile(
                    "src/A.java", null, "modified", 3, 1)),
                List.of(new tech.wenisch.petri.git.provider.PullRequestDetail.Comment(
                    "dave", "Looks good", Instant.now(), "APPROVED", null))),
            List.of(new PipelineRun("1", "CI", "SUCCESS", null, "feature", "abc", "push", null,
                Instant.now(), Instant.now())), null, null));

    mockMvc.perform(get("/projects/{id}/pull-requests", project.getId())
            .with(user("alice@petri.local").roles("USER")))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("#7 Add feature")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Draft")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("CI failed")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("CI passed")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("CI running")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("CI cancelled")));
    mockMvc.perform(get("/projects/{id}/pipelines", project.getId())
            .with(user("alice@petri.local").roles("USER")).param("embedded", "true"))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Failed")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("abcdef1")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("#7")));
    mockMvc.perform(get("/projects/{id}/pull-requests/{n}", project.getId(), 7)
            .with(user("alice@petri.local").roles("USER")).param("embedded", "true"))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Adds **feature**")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("A.java")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Looks good")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Open on provider")));
    mockMvc.perform(get("/projects/{id}/automations/runs/{run}/follow", project.getId(), UUID.randomUUID())
            .with(user("alice@petri.local").roles("USER")))
        .andExpect(status().isNotFound());
    mockMvc.perform(get("/projects/{id}/pull-requests", project.getId())
            .with(user("bob@petri.local").roles("USER")))
        .andExpect(status().isNotFound());
  }
}
