package tech.wenisch.petri.agent.model;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import tech.wenisch.petri.agent.tool.ToolDefinition;

final class OpenAiResponsesCodec {
  private final ObjectMapper objectMapper;

  OpenAiResponsesCodec(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  String encode(ModelRequest request) {
    ObjectNode body = objectMapper.createObjectNode();
    body.put("model", request.model());
    body.put("instructions", request.instructions());
    body.put("store", true);
    body.put("parallel_tool_calls", false);
    body.put("tool_choice", "auto");
    body.put("max_output_tokens", request.maxOutputTokens());
    if (request.previousResponseId() != null) {
      body.put("previous_response_id", request.previousResponseId());
    }
    if (request.prompt() != null) {
      body.put("input", request.prompt());
    } else {
      ArrayNode input = body.putArray("input");
      for (ModelToolOutput output : request.toolOutputs()) {
        input.addObject()
            .put("type", "function_call_output")
            .put("call_id", output.callId())
            .put("output", output.output());
      }
    }
    ArrayNode tools = body.putArray("tools");
    for (ToolDefinition definition : request.tools()) {
      ObjectNode tool = tools.addObject();
      tool.put("type", "function");
      tool.put("name", definition.name());
      tool.put("description", definition.description());
      tool.set("parameters", objectMapper.valueToTree(definition.inputSchema()));
      tool.put("strict", true);
    }
    try {
      return objectMapper.writeValueAsString(body);
    } catch (tools.jackson.core.JacksonException exception) {
      throw new ModelClientException("Could not encode model request", exception);
    }
  }

  ModelResponse decode(byte[] body) {
    try {
      JsonNode root = objectMapper.readTree(body);
      String id = requiredText(root, "id");
      String status = requiredText(root, "status");
      List<ModelToolCall> calls = new ArrayList<>();
      StringBuilder text = new StringBuilder();
      JsonNode output = root.path("output");
      if (!output.isArray()) throw new ModelClientException("Model response output is not an array");
      for (JsonNode item : output) {
        switch (item.path("type").asText()) {
          case "function_call" -> calls.add(new ModelToolCall(
              requiredText(item, "call_id"),
              requiredText(item, "name"),
              requiredText(item, "arguments")));
          case "message" -> appendText(item.path("content"), text);
          default -> { }
        }
      }
      return new ModelResponse(
          id,
          status,
          text.toString(),
          calls,
          root.path("usage").path("input_tokens").asInt(0),
          root.path("usage").path("output_tokens").asInt(0));
    } catch (tools.jackson.core.JacksonException exception) {
      throw new ModelClientException("Could not decode model response", exception);
    }
  }

  private static void appendText(JsonNode content, StringBuilder target) {
    if (!content.isArray()) return;
    for (JsonNode part : content) {
      if ("output_text".equals(part.path("type").asText())) {
        target.append(part.path("text").asText());
      }
    }
  }

  private static String requiredText(JsonNode node, String field) {
    JsonNode valueNode = node.path(field);
    String value = valueNode.isTextual() ? valueNode.textValue() : null;
    if (value == null || value.isBlank()) {
      throw new ModelClientException("Model response is missing " + field);
    }
    return value;
  }
}
