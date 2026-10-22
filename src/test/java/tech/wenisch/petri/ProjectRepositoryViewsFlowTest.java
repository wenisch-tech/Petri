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
  @Autowired tech.wenisch.petri.vulnerability.VulnerabilityReportService vulnerabilities;

  @Test
  void pipelineRunPageShowsTheLogReadOnlyAndIsOwnerScoped() throws Exception {
    AppUser alice = users.save(new AppUser(UUID.randomUUID(), "alice@petri.local", "unused", "USER"));
    users.save(new AppUser(UUID.randomUUID(), "bob@petri.local", "unused", "USER"));
    Project project = projects.save(new Project(UUID.randomUUID(), "Demo", "demo",
        "https://example.test/acme/demo.git", "main", alice.getId(), Instant.now()));
    var repository = new ProjectRepositoryActivityService.Repository(
        "FORGEJO", "acme/demo", "https://example.test/acme/demo");
    var failed = new tech.wenisch.petri.git.provider.PipelineJob("12", "unit-tests", "FAILED", null, null, null);
    var passed = new tech.wenisch.petri.git.provider.PipelineJob("11", "build", "SUCCESS", null, null, null);
    when(activity.pipelineRun(project.getId(), "alice@petri.local", "7", null))
        .thenReturn(new ProjectRepositoryActivityService.PipelineRunPage(repository,
            new PipelineRun("7", "CI", "FAILED", "failure", "main", "abcdef1234", "push",
                "https://example.test/runs/7", Instant.now(), Instant.now()),
            List.of(passed, failed), failed,
            new tech.wenisch.petri.git.provider.PipelineLog("ok\nERROR: </script><b>x</b>\n", false),
            null, null));

    mockMvc.perform(get("/projects/{id}/pipelines/{run}", project.getId(), "7")
            .with(user("alice@petri.local").roles("USER")).param("embedded", "true"))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("data-log-view")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("unit-tests")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("data-analyze-pipeline")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("data-run-id=\"7\"")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("&lt;/script&gt;&lt;b&gt;x")))
        .andExpect(content().string(org.hamcrest.Matchers.not(
            org.hamcrest.Matchers.containsString("<b>x</b>"))));
    mockMvc.perform(get("/projects/{id}/pipelines/{run}", project.getId(), "abc")
            .with(user("alice@petri.local").roles("USER")).param("embedded", "true"))
        .andExpect(status().isNotFound());
    mockMvc.perform(get("/projects/{id}/pipelines/{run}", project.getId(), "7")
            .with(user("bob@petri.local").roles("USER")))
        .andExpect(status().isNotFound());
    mockMvc.perform(get("/projects/{id}/skills", project.getId())
            .with(user("alice@petri.local").roles("USER")).param("embedded", "true"))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.not(
            org.hamcrest.Matchers.containsString("pipeline-log-analysis"))));
  }

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
    mockMvc.perform(get("/projects/{id}/skills", project.getId())
            .with(user("alice@petri.local").roles("USER")).param("embedded", "true"))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Run in the workspace")));
    mockMvc.perform(get("/projects/{id}/skills", project.getId())
            .with(user("bob@petri.local").roles("USER")))
        .andExpect(status().isNotFound());
    mockMvc.perform(get("/projects/{id}/vulnerabilities", project.getId())
            .with(user("alice@petri.local").roles("USER")).param("embedded", "true"))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("No vulnerability scan for this branch yet")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("data-run-skill=\"vulnerability-scan\"")));
    vulnerabilities.save(project.getId(), "main", null, "Trivy 0.76.0", "One real issue.", false,
        "Maven was not resolved", List.of(new tech.wenisch.petri.vulnerability.VulnerabilityReportService.Finding(
            "CVE-2026-1 in lib", "HIGH", "pom.xml: lib 1.0", "lib 1.0 is vulnerable", "lib 1.1",
            "Upgrade **lib**.", List.of("https://example.test/advisory"),
            List.of("CVE-2026-1"))), null);
    mockMvc.perform(get("/projects/{id}/vulnerabilities", project.getId())
            .with(user("alice@petri.local").roles("USER")).param("embedded", "true"))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("State today")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Target state")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("https://nvd.nist.gov/vuln/detail/CVE-2026-1")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("data-fix-prompt")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Fix all (1)")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("CVE-2026-1 in lib")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Maven was not resolved")));
    mockMvc.perform(get("/projects/{id}/automations/runs/{run}/follow", project.getId(), UUID.randomUUID())
            .with(user("alice@petri.local").roles("USER")))
        .andExpect(status().isNotFound());
    mockMvc.perform(get("/projects/{id}/pull-requests", project.getId())
            .with(user("bob@petri.local").roles("USER")))
        .andExpect(status().isNotFound());
  }
}
