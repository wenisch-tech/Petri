package tech.wenisch.petri.agent.orchestration;

import java.util.Iterator;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
class WorkflowArtifactParser {
  private final ObjectMapper mapper;
  WorkflowArtifactParser(ObjectMapper mapper) { this.mapper = mapper; }

  Parsed parse(AgentWorkflowStage phase, String output) {
    JsonNode root;
    String json = stripFence(output);
    try {
      root = mapper.readTree(json);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Agent did not return valid JSON", exception);
    }
    if (root == null || !root.isObject()) throw new IllegalArgumentException("Agent response must be a JSON object");
    WorkflowArtifactKind kind = switch (phase) {
      case ORCHESTRATOR_INTAKE -> validateBrief(root);
      case PLANNING -> validatePlanner(root);
      case ORCHESTRATOR_PLAN_REVIEW -> validateVerdict(root, WorkflowArtifactKind.PLAN_VERDICT);
      case IMPLEMENTING -> validateImplementation(root);
      case ORCHESTRATOR_IMPLEMENTATION_REVIEW -> validateVerdict(root, WorkflowArtifactKind.IMPLEMENTATION_VERDICT);
      case REVIEWING -> validateReview(root);
      case ORCHESTRATOR_FINAL_REVIEW -> validateVerdict(root, WorkflowArtifactKind.FINAL_VERDICT);
      default -> throw new IllegalArgumentException("Stage does not produce a model artifact");
    };
    return new Parsed(kind, json, markdown(kind, root), root);
  }

  private WorkflowArtifactKind validateBrief(JsonNode root) {
    text(root, "objective"); array(root, "successCriteria", 1, 30);
    return WorkflowArtifactKind.PLANNER_BRIEF;
  }

  private WorkflowArtifactKind validatePlanner(JsonNode root) {
    String kind = text(root, "kind");
    if ("QUESTIONS".equals(kind)) {
      JsonNode questions = array(root, "questions", 1, 5);
      for (JsonNode question : questions) {
        text(question, "id"); text(question, "prompt");
        String type = text(question, "type");
        if (!java.util.Set.of("SINGLE_SELECT", "MULTI_SELECT", "FREE_TEXT").contains(type)) {
          throw new IllegalArgumentException("Unsupported planner question type");
        }
        if (!"FREE_TEXT".equals(type)) array(question, "options", 2, 8);
      }
      return WorkflowArtifactKind.QUESTIONS;
    }
    if (!"PLAN".equals(kind)) throw new IllegalArgumentException("Planner kind must be QUESTIONS or PLAN");
    text(root, "title"); text(root, "objective"); array(root, "steps", 1, 100);
    array(root, "acceptanceCriteria", 1, 100);
    return WorkflowArtifactKind.PLAN;
  }

  private WorkflowArtifactKind validateVerdict(JsonNode root, WorkflowArtifactKind kind) {
    String decision = text(root, "decision");
    if (!java.util.Set.of("PASS", "REVISION_REQUIRED", "HOLD").contains(decision)) {
      throw new IllegalArgumentException("Gate decision must be PASS, REVISION_REQUIRED, or HOLD");
    }
    text(root, "reason");
    return kind;
  }

  private WorkflowArtifactKind validateImplementation(JsonNode root) {
    text(root, "summary"); array(root, "changedPaths", 0, 500);
    return WorkflowArtifactKind.IMPLEMENTATION_REPORT;
  }

  private WorkflowArtifactKind validateReview(JsonNode root) {
    String decision = text(root, "decision");
    if (!java.util.Set.of("APPROVED", "CHANGES_REQUIRED", "HOLD").contains(decision)) {
      throw new IllegalArgumentException("Review decision is invalid");
    }
    text(root, "summary"); array(root, "findings", 0, 200);
    return WorkflowArtifactKind.REVIEW;
  }

  private static String markdown(WorkflowArtifactKind kind, JsonNode root) {
    return switch (kind) {
      case PLAN -> "### " + root.path("title").asText("Plan") + "\n\n" + root.path("objective").asText();
      case QUESTIONS -> "Planner needs clarification before creating the plan.";
      case REVIEW -> "### Review\n\n" + root.path("summary").asText();
      case IMPLEMENTATION_REPORT -> "### Implementation\n\n" + root.path("summary").asText();
      case STAGE_FAILURE -> "Workflow stage failed.";
      default -> root.path("reason").asText(root.path("objective").asText("Workflow artifact"));
    };
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isTextual() || value.asText().isBlank()) {
      throw new IllegalArgumentException("Required text field is missing: " + field);
    }
    return value.asText();
  }

  private static JsonNode array(JsonNode node, String field, int min, int max) {
    JsonNode value = node.get(field);
    if (value == null || !value.isArray() || value.size() < min || value.size() > max) {
      throw new IllegalArgumentException("Array field has invalid size: " + field);
    }
    return value;
  }

  private static String stripFence(String value) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException("Agent returned no output");
    String result = value.strip();
    if (result.startsWith("```")) {
      int newline = result.indexOf('\n');
      int end = result.lastIndexOf("```");
      if (newline > 0 && end > newline) result = result.substring(newline + 1, end).strip();
    }
    return result;
  }

  record Parsed(WorkflowArtifactKind kind, String json, String markdown, JsonNode value) {}
}
