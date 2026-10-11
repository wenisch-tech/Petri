package tech.wenisch.petri.agent.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tech.wenisch.petri.agent.tool.ToolDefinition;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class OpenAiChatCompletionsClientTest {
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void ollamaUsesKeylessChatCompletionsWithReconstructedToolHistory() throws Exception {
    AtomicReference<String> authorization = new AtomicReference<>();
    AtomicReference<String> path = new AtomicReference<>();
    AtomicReference<String> requestBody = new AtomicReference<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext("/v1/chat/completions", exchange -> {
      authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
      path.set(exchange.getRequestURI().getPath());
      requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      byte[] body = ("""
          {"id":"chat_test","choices":[{"finish_reason":"tool_calls","message":{
            "role":"assistant","content":"Checking.","tool_calls":[{
              "id":"call_2","type":"function","function":{
                "name":"read_file","arguments":{"path":"pom.xml"}}}]}}],
           "usage":{"prompt_tokens":42,"completion_tokens":9}}
          """).getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.start();
    try {
      ModelCredentialService credentials = mock(ModelCredentialService.class);
      when(credentials.requireMaterial(ModelProvider.OLLAMA)).thenReturn(
          new ModelCredentialMaterial(
              ModelProvider.OLLAMA,
              null,
              "http://localhost:" + server.getAddress().getPort() + "/v1",
              "qwen3-coder"));
      OpenAiCompatibleTransport transport = new OpenAiCompatibleTransport(
          credentials, HttpClient.newHttpClient(), Duration.ofSeconds(5), 20_000);
      OllamaChatCompletionsClient client =
          new OllamaChatCompletionsClient(transport, objectMapper);
      ToolDefinition tool = new ToolDefinition(
          "read_file", "Read a file", Map.of("type", "object"));
      List<ModelConversationMessage> conversation = List.of(
          ModelConversationMessage.user("Inspect the project"),
          ModelConversationMessage.assistant(
              "",
              List.of(new ModelToolCall(
                  "call_1", "read_file", "{\"path\":\"README.md\"}"))),
          ModelConversationMessage.tool("call_1", "{\"content\":\"Petri\"}"));

      ModelResponse response = client.respond(new ModelRequest(
          "qwen3-coder",
          "Use tools.",
          null,
          "chat_previous",
          List.of(new ModelToolOutput("call_1", "{\"content\":\"Petri\"}")),
          List.of(tool),
          2048,
          ReasoningEffort.HIGH,
          null,
          conversation));

      assertThat(path.get()).isEqualTo("/v1/chat/completions");
      assertThat(authorization.get()).isNull();
      JsonNode sent = objectMapper.readTree(requestBody.get());
      assertThat(sent.path("model").asText()).isEqualTo("qwen3-coder");
      assertThat(sent.path("max_tokens").asInt()).isEqualTo(2048);
      assertThat(sent.path("reasoning_effort").asText()).isEqualTo("high");
      assertThat(sent.path("messages").get(2).path("tool_calls").get(0)
          .path("function").path("name").asText()).isEqualTo("read_file");
      assertThat(sent.path("messages").get(3).path("role").asText()).isEqualTo("tool");
      assertThat(response.status()).isEqualTo("completed");
      assertThat(response.text()).isEqualTo("Checking.");
      assertThat(response.toolCalls()).containsExactly(
          new ModelToolCall("call_2", "read_file", "{\"path\":\"pom.xml\"}"));
      assertThat(response.inputTokens()).isEqualTo(42);
      assertThat(response.outputTokens()).isEqualTo(9);
    } finally {
      server.stop(0);
    }
  }

  @Test
  void genericCompatibilityPayloadOmitsProviderSpecificReasoningControls() throws Exception {
    OpenAiChatCompletionsCodec codec = new OpenAiChatCompletionsCodec(objectMapper, false);
    JsonNode request = objectMapper.readTree(codec.encode(new ModelRequest(
        "local-model",
        "Be useful.",
        "Hello",
        null,
        List.of(),
        List.of(),
        512,
        ReasoningEffort.HIGH,
        TextVerbosity.HIGH)));

    assertThat(request.has("reasoning_effort")).isFalse();
    assertThat(request.has("verbosity")).isFalse();
    assertThat(request.path("messages").get(1).path("content").asText()).isEqualTo("Hello");
  }

  @Test
  void encodesJsonObjectOutputForWorkflowStages() throws Exception {
    OpenAiChatCompletionsCodec codec = new OpenAiChatCompletionsCodec(objectMapper, true);
    JsonNode request = objectMapper.readTree(codec.encode(new ModelRequest(
        "local-model", "Return JSON.", "Create the artifact", null, List.of(), List.of(),
        512, null, null, List.of(), true)));

    assertThat(request.path("response_format").path("type").asText()).isEqualTo("json_object");
  }
}
