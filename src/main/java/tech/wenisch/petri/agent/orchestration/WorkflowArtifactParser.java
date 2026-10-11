package tech.wenisch.petri.agent.orchestration;

import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
class WorkflowArtifactParser {
  private final ObjectMapper mapper;
  WorkflowArtifactParser(ObjectMapper mapper) { this.mapper = mapper; }

  Parsed parse(AgentWorkflowStage phase, String output) {
    if (output == null || output.isBlank()) {
      throw new IllegalArgumentException("Model returned no structured workflow response");
    }
    IllegalArgumentException validationFailure = null;
    for (String candidate : jsonCandidates(output)) {
      try {
        JsonNode root = mapper.readTree(candidate);
        if (root == null || !root.isObject()) continue;
        WorkflowArtifactKind kind = validate(phase, root);
        String canonical = mapper.writeValueAsString(root);
        return new Parsed(kind, canonical, markdown(kind, root), root);
      } catch (JacksonException ignored) {
        // Local models occasionally surround the JSON with prose. Try the next balanced object.
      } catch (IllegalArgumentException exception) {
        validationFailure = exception;
      }
    }
    if (validationFailure != null) throw validationFailure;
    throw new IllegalArgumentException(
        "Model response did not contain one complete JSON object; Petri will retry the stage");
  }

  private WorkflowArtifactKind validate(AgentWorkflowStage phase, JsonNode root) {
    return switch (phase) {
      case ORCHESTRATOR_INTAKE -> validateBrief(root);
      case PLANNING -> validatePlanner(root);
      case ORCHESTRATOR_PLAN_REVIEW -> validatePlanVerdict(root);
      case IMPLEMENTING -> validateImplementation(root);
      case ORCHESTRATOR_IMPLEMENTATION_REVIEW -> validateVerdict(root, WorkflowArtifactKind.IMPLEMENTATION_VERDICT);
      case REVIEWING -> validateReview(root);
      case ORCHESTRATOR_FINAL_REVIEW -> validateVerdict(root, WorkflowArtifactKind.FINAL_VERDICT);
      default -> throw new IllegalArgumentException("Stage does not produce a model artifact");
    };
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

  private WorkflowArtifactKind validatePlanVerdict(JsonNode root) {
    String decision = text(root, "decision");
    if (!java.util.Set.of("PASS", "PASS_WITH_WARNINGS", "REVISION_REQUIRED", "HOLD")
        .contains(decision)) {
      throw new IllegalArgumentException(
          "Plan gate decision must be PASS, PASS_WITH_WARNINGS, REVISION_REQUIRED, or HOLD");
    }
    text(root, "reason");
    array(root, "feedback", 0, 100);
    JsonNode warnings = array(root, "warnings", 0, 100);
    JsonNode hardBlockNode = root.get("hardBlock");
    if (hardBlockNode == null || !hardBlockNode.isBoolean()) {
      throw new IllegalArgumentException("Plan gate hardBlock must be true or false");
    }
    boolean hardBlock = hardBlockNode.asBoolean();
    if ("HOLD".equals(decision) != hardBlock) {
      throw new IllegalArgumentException("Only HOLD may set hardBlock=true, and HOLD requires it");
    }
    if ("PASS_WITH_WARNINGS".equals(decision) && warnings.isEmpty()) {
      throw new IllegalArgumentException("PASS_WITH_WARNINGS requires at least one warning");
    }
    return WorkflowArtifactKind.PLAN_VERDICT;
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

  private static Set<String> jsonCandidates(String value) {
    String input = value.replace("\uFEFF", "").strip();
    Set<String> candidates = new LinkedHashSet<>();
    candidates.add(input);
    int depth = 0;
    int start = -1;
    boolean quoted = false;
    boolean escaped = false;
    for (int index = 0; index < input.length(); index++) {
      char current = input.charAt(index);
      if (quoted) {
        if (escaped) escaped = false;
        else if (current == '\\') escaped = true;
        else if (current == '"') quoted = false;
        continue;
      }
      if (current == '"') {
        quoted = true;
      } else if (current == '{') {
        if (depth++ == 0) start = index;
      } else if (current == '}' && depth > 0 && --depth == 0 && start >= 0) {
        candidates.add(input.substring(start, index + 1));
        start = -1;
      }
    }
    return candidates;
  }

  record Parsed(WorkflowArtifactKind kind, String json, String markdown, JsonNode value) {}
}
