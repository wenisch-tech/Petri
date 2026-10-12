package tech.wenisch.petri.agent.orchestration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class WorkflowArtifactParserTest {
  private final WorkflowArtifactParser parser = new WorkflowArtifactParser(new ObjectMapper());

  @Test
  void acceptsAPlanAndStructuredQuestions() {
    var plan = parser.parse(AgentWorkflowStage.PLANNING, """
        {"kind":"PLAN","title":"Safe change","objective":"Implement it",\
        "steps":["inspect","change"],"acceptanceCriteria":["tests pass"]}
        """);
    var questions = parser.parse(AgentWorkflowStage.PLANNING, """
        {"kind":"QUESTIONS","questions":[{"id":"target","type":"SINGLE_SELECT",\
        "prompt":"Which target?","options":["A","B"]}]}
        """);

    assertThat(plan.kind()).isEqualTo(WorkflowArtifactKind.PLAN);
    assertThat(questions.kind()).isEqualTo(WorkflowArtifactKind.QUESTIONS);
  }

  @Test
  void rejectsUnknownGateDecisionsAndMalformedJson() {
    assertThatThrownBy(() -> parser.parse(AgentWorkflowStage.ORCHESTRATOR_PLAN_REVIEW,
        "{\"decision\":\"MAYBE\",\"reason\":\"unclear\"}"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> parser.parse(AgentWorkflowStage.REVIEWING, "not json"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void limitsPlannerQuestionsAndValidatesChoiceOptions() {
    assertThatThrownBy(() -> parser.parse(AgentWorkflowStage.PLANNING, """
        {"kind":"QUESTIONS","questions":[{"id":"q","type":"MULTI_SELECT",\
        "prompt":"Choose","options":["only one"]}]}
        """))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("options");
  }

  @Test
  void extractsACompleteObjectFromFencesAndModelCommentary() {
    var fenced = parser.parse(AgentWorkflowStage.ORCHESTRATOR_PLAN_REVIEW, """
        I checked the evidence. Here is the result:
        ```json
        {"decision":"PASS","reason":"Plan is complete","feedback":[],"warnings":[],"hardBlock":false}
        ```
        """);

    assertThat(fenced.kind()).isEqualTo(WorkflowArtifactKind.PLAN_VERDICT);
    assertThat(fenced.json()).isEqualTo(
        "{\"decision\":\"PASS\",\"reason\":\"Plan is complete\",\"feedback\":[],"
            + "\"warnings\":[],\"hardBlock\":false}");
  }

  @Test
  void ignoresBracesInsideJsonStringsWhenExtracting() {
    var report = parser.parse(AgentWorkflowStage.IMPLEMENTING,
        "Result: {\"summary\":\"Handled {nested} text\",\"changedPaths\":[]} done");

    assertThat(report.value().path("summary").asText()).isEqualTo("Handled {nested} text");
  }

  @Test
  void acceptsPlanWarningsButRequiresHardBlocksToBeExplicit() {
    var warning = parser.parse(AgentWorkflowStage.ORCHESTRATOR_PLAN_REVIEW, """
        {"decision":"PASS_WITH_WARNINGS","reason":"Defaults need confirmation",\
        "feedback":[],"warnings":["Java version is a default"],"hardBlock":false}
        """);

    assertThat(warning.value().path("decision").asText()).isEqualTo("PASS_WITH_WARNINGS");
    assertThatThrownBy(() -> parser.parse(AgentWorkflowStage.ORCHESTRATOR_PLAN_REVIEW, """
        {"decision":"HOLD","reason":"Unverified assumption","feedback":[],"warnings":[],\
        "hardBlock":false}
        """))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("HOLD");
  }

  @Test
  void numbersLegacyCriteriaAndKeepsGivenIds() {
    var legacy = parser.parse(AgentWorkflowStage.PLANNING, """
        {"kind":"PLAN","title":"t","objective":"o","steps":["s"],\
        "acceptanceCriteria":["tests pass","docs updated"]}
        """);
    var criteria = legacy.value().path("acceptanceCriteria");
    assertThat(criteria.get(0).path("id").asText()).isEqualTo("AC1");
    assertThat(criteria.get(1).path("text").asText()).isEqualTo("docs updated");
    assertThat(legacy.json()).contains("\"id\":\"AC2\"");

    var explicit = parser.parse(AgentWorkflowStage.PLANNING, """
        {"kind":"PLAN","title":"t","objective":"o","steps":["s"],\
        "acceptanceCriteria":[{"id":"LOGIN","text":"login works"},"second"]}
        """);
    assertThat(explicit.value().path("acceptanceCriteria").get(0).path("id").asText())
        .isEqualTo("LOGIN");
    assertThat(explicit.value().path("acceptanceCriteria").get(1).path("id").asText())
        .isEqualTo("AC2");
  }

  @Test
  void rejectsDuplicateOrBlankCriteria() {
    assertThatThrownBy(() -> parser.parse(AgentWorkflowStage.PLANNING, """
        {"kind":"PLAN","title":"t","objective":"o","steps":["s"],\
        "acceptanceCriteria":[{"id":"A","text":"x"},{"id":"A","text":"y"}]}
        """)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Duplicate");
    assertThatThrownBy(() -> parser.parse(AgentWorkflowStage.PLANNING, """
        {"kind":"PLAN","title":"t","objective":"o","steps":["s"],\
        "acceptanceCriteria":[{"id":"A","text":"  "}]}
        """)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void requiresWellFormedPlanCoverageInReviews() {
    var review = parser.parse(AgentWorkflowStage.REVIEWING, """
        {"decision":"APPROVED","summary":"ok","findings":[],"planCoverage":[\
        {"criterionId":"AC1","status":"MET","evidence":[{"type":"TEST","ref":"abc"}],"note":"n"}]}
        """);
    assertThat(review.kind()).isEqualTo(WorkflowArtifactKind.REVIEW);

    assertThatThrownBy(() -> parser.parse(AgentWorkflowStage.REVIEWING,
        "{\"decision\":\"APPROVED\",\"summary\":\"ok\",\"findings\":[]}"))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("planCoverage");
    assertThatThrownBy(() -> parser.parse(AgentWorkflowStage.REVIEWING, """
        {"decision":"APPROVED","summary":"ok","findings":[],"planCoverage":[\
        {"criterionId":"AC1","status":"DONE","evidence":[]}]}
        """)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> parser.parse(AgentWorkflowStage.REVIEWING, """
        {"decision":"APPROVED","summary":"ok","findings":[],"planCoverage":[\
        {"criterionId":"AC1","status":"MET","evidence":[{"type":"URL","ref":"x"}]}]}
        """)).isInstanceOf(IllegalArgumentException.class);
  }
}
