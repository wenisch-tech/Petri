package tech.wenisch.petri.agent.model;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
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
    if (request.reasoningEffort() != null) {
      body.putObject("reasoning").put("effort", request.reasoningEffort().apiValue());
    }
    if (request.textVerbosity() != null || request.jsonObjectOutput()) {
      ObjectNode text = body.putObject("text");
      if (request.textVerbosity() != null) {
        text.put("verbosity", request.textVerbosity().apiValue());
      }
      if (request.jsonObjectOutput()) {
        text.putObject("format").put("type", "json_object");
      }
    }
    if (request.previousResponseId() != null) {
      body.put("previous_response_id", request.previousResponseId());
    }
    if (request.previousResponseId() == null && !request.conversation().isEmpty()) {
      ArrayNode input = body.putArray("input");
      for (ModelConversationMessage message : request.conversation()) {
        ObjectNode item = input.addObject();
        switch (message.role()) {
          case USER -> item.put("role", "user").put("content", message.content());
          case ASSISTANT -> item.put("role", "assistant").put("content", message.content());
          case TOOL -> item.put("role", "user").put("content",
              "Tool result for " + message.toolCallId() + ": " + message.content());
        }
      }
    } else if (request.prompt() != null) {
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
      tool.put("strict", definition.strict());
    }
    try {
      return objectMapper.writeValueAsString(body);
    } catch (tools.jackson.core.JacksonException exception) {
      throw new ModelClientException("Could not encode model request", exception);
    }
  }

  String encodeStreaming(ModelRequest request) {
    try {
      ObjectNode body = (ObjectNode) objectMapper.readTree(encode(request));
      body.put("stream", true);
      return objectMapper.writeValueAsString(body);
    } catch (tools.jackson.core.JacksonException exception) {
      throw new ModelClientException("Could not encode streaming model request", exception);
    }
  }

  ModelResponse stream(
      OpenAiCompatibleTransport transport,
      ModelProvider provider,
      ModelRequest request,
      ModelStreamListener listener) {
    AtomicReference<ModelResponse> completed = new AtomicReference<>();
    transport.postSse(provider, request.credential(), "/responses", encodeStreaming(request), data -> {
      if ("[DONE]".equals(data)) return;
      try {
        JsonNode event = objectMapper.readTree(data);
        String type = event.path("type").asText();
        if ("response.output_text.delta".equals(type)) {
          String delta = event.path("delta").asText("");
          if (!delta.isEmpty()) listener.onTextDelta(delta);
        } else if ("response.function_call_arguments.delta".equals(type)) {
          listener.onToolCallDelta(event.path("output_index").asInt(),
              event.path("call_id").asText(null), event.path("name").asText(null),
              event.path("delta").asText(""));
        } else if ("response.completed".equals(type)) {
          JsonNode response = event.path("response");
          ModelResponse value = decodeNode(response.isMissingNode() ? event : response);
          completed.set(value);
          listener.onUsage(value.inputTokens(), value.outputTokens());
          listener.onCompleted(value);
        } else if ("error".equals(type)) {
          String message = event.path("message").asText("unknown error");
          listener.onError(message);
          throw new ModelClientException("Model streaming response failed: " + message);
        }
      } catch (tools.jackson.core.JacksonException exception) {
        throw new ModelClientException("Could not decode streaming model response", exception);
      }
    });
    ModelResponse response = completed.get();
    if (response == null) throw new ModelClientException("Model streaming response did not complete");
    return response;
  }

  ModelResponse decode(byte[] body) {
    try {
      return decodeNode(objectMapper.readTree(body));
    } catch (tools.jackson.core.JacksonException exception) {
      throw new ModelClientException("Could not decode model response", exception);
    }
  }

  private ModelResponse decodeNode(JsonNode root) {
    try {
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
