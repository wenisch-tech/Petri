package tech.wenisch.petri.automation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.agent.tool.AgentToolRegistry;
import tech.wenisch.petri.agent.tool.ToolExecutionContext;
import tech.wenisch.petri.project.Project;
import tech.wenisch.petri.project.ProjectRepository;
import tech.wenisch.petri.security.AppUserRepository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@Transactional
class SubmitReviewWithRealBeansTest {
  private static final String ARGUMENTS = """
      {"summary": "Docs only.", "verdict": "COMMENT", "findings": [
        {"body": "Placeholder URL.", "line": 9, "path": "CONTRIBUTING.md", "previousId": null,
         "severity": "MINOR", "suggestion": null, "title": "Placeholder clone URL"}],
       "comparison": null}
      """;

  @Autowired AgentToolRegistry registry;
  @Autowired ObjectMapper json;
  @Autowired AutomationRunRepository runs;
  @Autowired ProjectRepository projects;
  @Autowired AppUserRepository users;

  @Test
  void theToolStoresTheReviewWhenCalledLikeTheAgentExecutorCallsIt() throws Exception {
    UUID projectId = UUID.randomUUID();
    projects.saveAndFlush(new Project(projectId, "Real beans", "real-beans-" + projectId,
        "https://example.test/acme/repo.git", "main",
        users.findByEmailIgnoreCase("test-admin@petri.local").orElseThrow().getId(), Instant.now()));
    AutomationRun run = new AutomationRun(UUID.randomUUID(), projectId, AutomationKind.CODE_REVIEW,
        AutomationTrigger.PULL_REQUEST, "14", "abc12345", "Contributing", null,
        AutomationPostMode.AUTO, Instant.now());
    run.workspace(UUID.randomUUID(), "CONTRIBUTING.md", Instant.now());
    runs.saveAndFlush(run);
    Map<String, Object> arguments = json.readValue(ARGUMENTS, new TypeReference<>() {});

    var result = registry.execute("submit_review",
        new ToolExecutionContext(UUID.randomUUID(), UUID.randomUUID(), null, run.getId()), arguments);

    assertThat(result.output().toString()).contains("submitted");
    AutomationRun stored = runs.findById(run.getId()).orElseThrow();
    assertThat(stored.getReviewMarkdown()).contains("Placeholder clone URL");
    assertThat(stored.getReviewJson()).contains("MINOR");
  }
}
