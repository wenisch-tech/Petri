package tech.wenisch.petri.agent.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    if (request.jsonObjectOutput()) {
      body.putObject("response_format").put("type", "json_object");
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

  String encodeStreaming(ModelRequest request) {
    try {
      ObjectNode body = (ObjectNode) objectMapper.readTree(encode(request));
      body.put("stream", true);
      return objectMapper.writeValueAsString(body);
    } catch (JacksonException exception) {
      throw new ModelClientException("Could not encode streaming model request", exception);
    }
  }

  ModelResponse stream(
      OpenAiCompatibleTransport transport,
      ModelProvider provider,
      ModelRequest request,
      ModelStreamListener listener) {
    StringBuilder text = new StringBuilder();
    Map<Integer, StreamToolCall> calls = new HashMap<>();
    String[] responseId = {null};
    String[] finish = {null};
    int[] usage = {0, 0};
    transport.postSse(provider, request.credential(), "/chat/completions", encodeStreaming(request), data -> {
      if ("[DONE]".equals(data)) return;
      try {
        JsonNode root = objectMapper.readTree(data);
        if (root.path("id").isTextual()) responseId[0] = root.path("id").textValue();
        JsonNode choice = root.path("choices").path(0);
        JsonNode delta = choice.path("delta");
        String piece = optionalText(delta, "content");
        if (piece != null) { text.append(piece); listener.onTextDelta(piece); }
        JsonNode toolCalls = delta.path("tool_calls");
        if (toolCalls.isArray()) for (JsonNode item : toolCalls) {
          int index = item.path("index").asInt();
          // Some providers repeat the id and name as empty strings in later chunks and some send
          // parallel calls with the same index; neither may overwrite or merge a call.
          String chunkId = item.path("id").isTextual() && !item.path("id").textValue().isBlank()
              ? item.path("id").textValue() : null;
          StreamToolCall existing = calls.get(index);
          if (existing != null && chunkId != null && existing.id != null && !existing.id.equals(chunkId)) {
            index = calls.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1) + 1;
          }
          StreamToolCall call = calls.computeIfAbsent(index, ignored -> new StreamToolCall());
          if (chunkId != null) call.id = chunkId;
          JsonNode function = item.path("function");
          if (function.path("name").isTextual() && !function.path("name").textValue().isBlank()) {
            call.name = function.path("name").textValue();
          }
          String argumentsDelta = function.path("arguments").isTextual()
              ? function.path("arguments").textValue() : "";
          if (!argumentsDelta.isEmpty()) call.arguments.append(argumentsDelta);
          listener.onToolCallDelta(index, call.id, call.name, argumentsDelta);
        }
        // A trailing usage chunk has no choices; its missing finish_reason must not blank the status.
        if (choice.path("finish_reason").isTextual() && !choice.path("finish_reason").textValue().isBlank()) {
          finish[0] = choice.path("finish_reason").textValue();
        }
        usage[0] = root.path("usage").path("prompt_tokens").asInt(usage[0]);
        usage[1] = root.path("usage").path("completion_tokens").asInt(usage[1]);
      } catch (JacksonException exception) {
        throw new ModelClientException("Could not decode streaming model response", exception);
      }
    });
    List<ModelToolCall> modelCalls = normalised(calls.entrySet().stream()
        .sorted(Map.Entry.comparingByKey()).map(entry -> new ModelToolCall(
            entry.getValue().id, entry.getValue().name, entry.getValue().arguments.toString()))
        .toList());
    String status = "stop".equals(finish[0]) || "tool_calls".equals(finish[0])
        ? "completed" : finish[0] == null ? "incomplete" : finish[0];
    ModelResponse response = new ModelResponse(responseId[0] == null ? "chatcmpl_" + UUID.randomUUID() : responseId[0],
        status, text.toString(), modelCalls, usage[0], usage[1]);
    listener.onUsage(response.inputTokens(), response.outputTokens());
    if ("completed".equals(status)) listener.onCompleted(response);
    else listener.onError("Model streaming response ended with status " + status);
    return response;
  }

  private static final class StreamToolCall {
    private String id;
    private String name;
    private final StringBuilder arguments = new StringBuilder();
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
          String name = requiredText(function, "name");
          JsonNode arguments = function.path("arguments");
          String argumentJson = arguments.isTextual()
              ? arguments.textValue()
              : objectMapper.writeValueAsString(arguments);
          calls.add(new ModelToolCall(id, name, argumentJson));
        }
        calls = normalised(calls);
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

  /**
   * Makes tool calls safe to persist: a missing or repeated call id gets a fresh one, a call
   * without a name keeps a placeholder the tool registry then refuses with a clear message, and
   * empty arguments (common for tools without required input) become an empty object.
   */
  static List<ModelToolCall> normalised(List<ModelToolCall> calls) {
    java.util.Set<String> seen = new java.util.HashSet<>();
    List<ModelToolCall> result = new ArrayList<>();
    for (ModelToolCall call : calls) {
      String id = call.callId() == null || call.callId().isBlank() || !seen.add(call.callId())
          ? "call_" + UUID.randomUUID() : call.callId();
      seen.add(id);
      String name = call.name() == null || call.name().isBlank() ? "unknown" : call.name();
      String arguments = call.argumentsJson() == null || call.argumentsJson().isBlank()
          ? "{}" : call.argumentsJson();
      result.add(new ModelToolCall(id, name, arguments));
    }
    return result;
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
