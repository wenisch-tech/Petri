package tech.wenisch.petri.agent.model;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import tech.wenisch.petri.agent.tool.ToolDefinition;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

final class OpenAiChatCompletionsCodec {
  private final ObjectMapper objectMapper;
  private final boolean includeReasoningEffort;

  OpenAiChatCompletionsCodec(ObjectMapper objectMapper, boolean includeReasoningEffort) {
    this.objectMapper = objectMapper;
    this.includeReasoningEffort = includeReasoningEffort;
  }

  String encode(ModelRequest request) {
    ObjectNode body = objectMapper.createObjectNode();
    body.put("model", request.model());
    body.put("max_tokens", request.maxOutputTokens());
    if (includeReasoningEffort && request.reasoningEffort() != null) {
      body.put("reasoning_effort", request.reasoningEffort().apiValue());
    }
    ArrayNode messages = body.putArray("messages");
    messages.addObject().put("role", "system").put("content", request.instructions());
    if (request.conversation().isEmpty()) {
      messages.addObject().put("role", "user").put("content", request.prompt());
    } else {
      request.conversation().forEach(message -> encodeMessage(messages, message));
    }
    if (!request.tools().isEmpty()) {
      ArrayNode tools = body.putArray("tools");
      for (ToolDefinition definition : request.tools()) {
        ObjectNode function = tools.addObject().put("type", "function").putObject("function");
        function.put("name", definition.name());
        function.put("description", definition.description());
        function.set("parameters", objectMapper.valueToTree(definition.inputSchema()));
      }
    }
    try {
      return objectMapper.writeValueAsString(body);
    } catch (JacksonException exception) {
      throw new ModelClientException("Could not encode model request", exception);
    }
  }

  ModelResponse decode(byte[] body) {
    try {
      JsonNode root = objectMapper.readTree(body);
      JsonNode choice = root.path("choices").path(0);
      if (choice.isMissingNode()) {
        throw new ModelClientException("Model response has no choices");
      }
      JsonNode message = choice.path("message");
      String finishReason = choice.path("finish_reason").asText();
      String status = "stop".equals(finishReason) || "tool_calls".equals(finishReason)
          ? "completed"
          : finishReason.isBlank() ? "incomplete" : finishReason;
      List<ModelToolCall> calls = new ArrayList<>();
      JsonNode toolCalls = message.path("tool_calls");
      if (toolCalls.isArray()) {
        for (JsonNode call : toolCalls) {
          JsonNode function = call.path("function");
          String id = optionalText(call, "id");
          if (id == null) id = "call_" + UUID.randomUUID();
          String name = requiredText(function, "name");
          JsonNode arguments = function.path("arguments");
          String argumentJson = arguments.isTextual()
              ? arguments.textValue()
              : objectMapper.writeValueAsString(arguments);
          calls.add(new ModelToolCall(id, name, argumentJson));
        }
      }
      String responseId = optionalText(root, "id");
      if (responseId == null) responseId = "chatcmpl_" + UUID.randomUUID();
      return new ModelResponse(
          responseId,
          status,
          content(message.path("content")),
          calls,
          root.path("usage").path("prompt_tokens").asInt(0),
          root.path("usage").path("completion_tokens").asInt(0));
    } catch (JacksonException exception) {
      throw new ModelClientException("Could not decode model response", exception);
    }
  }

  private void encodeMessage(ArrayNode messages, ModelConversationMessage message) {
    ObjectNode encoded = messages.addObject();
    switch (message.role()) {
      case USER -> encoded.put("role", "user").put("content", message.content());
      case TOOL -> encoded.put("role", "tool")
          .put("tool_call_id", message.toolCallId())
          .put("content", message.content());
      case ASSISTANT -> {
        encoded.put("role", "assistant").put("content", message.content());
        if (!message.toolCalls().isEmpty()) {
          ArrayNode calls = encoded.putArray("tool_calls");
          for (ModelToolCall call : message.toolCalls()) {
            ObjectNode item = calls.addObject();
            item.put("id", call.callId());
            item.put("type", "function");
            item.putObject("function")
                .put("name", call.name())
                .put("arguments", call.argumentsJson());
          }
        }
      }
    }
  }

  private static String content(JsonNode value) {
    if (value.isTextual()) return value.textValue();
    if (!value.isArray()) return "";
    StringBuilder text = new StringBuilder();
    for (JsonNode part : value) {
      if (part.path("text").isTextual()) text.append(part.path("text").textValue());
    }
    return text.toString();
  }

  private static String requiredText(JsonNode node, String field) {
    String value = optionalText(node, field);
    if (value == null) throw new ModelClientException("Model response is missing " + field);
    return value;
  }

  private static String optionalText(JsonNode node, String field) {
    JsonNode value = node.path(field);
    return value.isTextual() && !value.textValue().isBlank() ? value.textValue() : null;
  }
}
