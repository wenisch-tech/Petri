package tech.wenisch.petri.agent.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
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
import tech.wenisch.petri.config.PetriProperties;

class OpenAiResponsesClientTest {
  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void sendsStrictResponsesRequestAndDecodesTextAndToolCalls() throws Exception {
    AtomicReference<String> authorization = new AtomicReference<>();
    AtomicReference<String> requestBody = new AtomicReference<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext("/v1/responses", exchange -> {
      authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
      requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
      byte[] body = ("""
          {"id":"resp_test","status":"completed","output":[
            {"type":"message","content":[{"type":"output_text","text":"Inspecting."}]},
            {"type":"function_call","call_id":"call_1","name":"read_file","arguments":"{\\"path\\":\\"README.md\\"}"}
          ],"usage":{"input_tokens":12,"output_tokens":7}}
          """).getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "application/json");
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.start();
    try {
      ModelCredentialService credentials = mock(ModelCredentialService.class);
      when(credentials.requireMaterial(ModelProvider.OPENAI))
          .thenReturn(new ModelCredentialMaterial(
              ModelProvider.OPENAI,
              "secret-key",
              "http://localhost:" + server.getAddress().getPort() + "/v1",
              "test-model"));
      PetriProperties properties = new PetriProperties(
          null,
          null,
          null,
          new PetriProperties.Agent(
              "http://localhost:" + server.getAddress().getPort() + "/v1",
              Duration.ofSeconds(5),
              20_000,
              1000,
              null,
              null));
      OpenAiResponsesClient client = new OpenAiResponsesClient(
          credentials,
          HttpClient.newHttpClient(),
          new OpenAiResponsesCodec(objectMapper),
          properties);
      ToolDefinition tool = new ToolDefinition(
          "read_file",
          "Read a file",
          Map.of(
              "type", "object",
              "properties", Map.of("path", Map.of("type", "string")),
              "required", List.of("path"),
              "additionalProperties", false));

      ModelResponse response = client.respond(new ModelRequest(
          "test-model", "Use tools carefully.", "Inspect the project.", null,
          List.of(), List.of(tool), 1000, ReasoningEffort.HIGH, TextVerbosity.LOW));

      assertThat(authorization.get()).isEqualTo("Bearer secret-key");
      JsonNode sent = objectMapper.readTree(requestBody.get());
      assertThat(sent.path("model").asText()).isEqualTo("test-model");
      assertThat(sent.path("parallel_tool_calls").asBoolean()).isFalse();
      assertThat(sent.path("store").asBoolean()).isTrue();
      assertThat(sent.path("max_output_tokens").asInt()).isEqualTo(1000);
      assertThat(sent.path("reasoning").path("effort").asText()).isEqualTo("high");
      assertThat(sent.path("text").path("verbosity").asText()).isEqualTo("low");
      assertThat(sent.path("tools").get(0).path("strict").asBoolean()).isTrue();
      assertThat(response.responseId()).isEqualTo("resp_test");
      assertThat(response.text()).isEqualTo("Inspecting.");
      assertThat(response.toolCalls()).containsExactly(
          new ModelToolCall("call_1", "read_file", "{\"path\":\"README.md\"}"));
      assertThat(response.inputTokens()).isEqualTo(12);
      assertThat(response.outputTokens()).isEqualTo(7);
    } finally {
      server.stop(0);
    }
  }

  @Test
  void encodesFunctionOutputsAgainstPreviousResponse() throws Exception {
    OpenAiResponsesCodec codec = new OpenAiResponsesCodec(objectMapper);
    JsonNode request = objectMapper.readTree(codec.encode(new ModelRequest(
        "test-model",
        "Continue.",
        null,
        "resp_previous",
        List.of(new ModelToolOutput("call_1", "{\"content\":\"ok\"}")),
        List.of(),
        100)));

    assertThat(request.path("previous_response_id").asText()).isEqualTo("resp_previous");
    assertThat(request.path("input").get(0).path("type").asText())
        .isEqualTo("function_call_output");
    assertThat(request.path("input").get(0).path("call_id").asText()).isEqualTo("call_1");
  }

  @Test
  void liteLlmUsesConfiguredResponsesEndpointAndVirtualKey() throws Exception {
    AtomicReference<String> authorization = new AtomicReference<>();
    AtomicReference<String> path = new AtomicReference<>();
    HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext("/gateway/v1/responses", exchange -> {
      authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
      path.set(exchange.getRequestURI().getPath());
      byte[] body = """
          {"id":"resp_litellm","status":"completed","output":[
            {"type":"message","content":[{"type":"output_text","text":"Ready."}]}
          ],"usage":{"input_tokens":3,"output_tokens":2}}
          """.getBytes(StandardCharsets.UTF_8);
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.start();
    try {
      ModelCredentialService credentials = mock(ModelCredentialService.class);
      when(credentials.requireMaterial(ModelProvider.LITELLM)).thenReturn(
          new ModelCredentialMaterial(
              ModelProvider.LITELLM,
              "litellm-virtual-key",
              "http://localhost:" + server.getAddress().getPort() + "/gateway/v1",
              "coding-model"));
      OpenAiCompatibleTransport transport = new OpenAiCompatibleTransport(
          credentials, HttpClient.newHttpClient(), Duration.ofSeconds(5), 20_000);
      LiteLlmResponsesClient client = new LiteLlmResponsesClient(transport, objectMapper);

      ModelResponse response = client.respond(new ModelRequest(
          "coding-model", "Be useful.", "Hello", null, List.of(), List.of(), 256));

      assertThat(path.get()).isEqualTo("/gateway/v1/responses");
      assertThat(authorization.get()).isEqualTo("Bearer litellm-virtual-key");
      assertThat(response.responseId()).isEqualTo("resp_litellm");
      assertThat(response.text()).isEqualTo("Ready.");
    } finally {
      server.stop(0);
    }
  }
}
