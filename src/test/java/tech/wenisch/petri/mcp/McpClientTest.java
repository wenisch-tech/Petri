package tech.wenisch.petri.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class McpClientTest {
  private final ObjectMapper json = new ObjectMapper();
  private final List<String> methods = new CopyOnWriteArrayList<>();
  private final List<String> sessions = new CopyOnWriteArrayList<>();
  private HttpServer server;
  private McpClient client;

  @BeforeEach
  void start() throws IOException {
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext("/mcp", this::handle);
    server.start();
    client = new McpClient(HttpClient.newHttpClient(), json, Duration.ofSeconds(5), 4096, "test");
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private String url() {
    return "http://localhost:" + server.getAddress().getPort() + "/mcp";
  }

  private void handle(HttpExchange exchange) throws IOException {
    if (!"Bearer good".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
      exchange.getResponseHeaders().add("WWW-Authenticate", "Bearer resource_metadata=\"x\"");
      exchange.sendResponseHeaders(401, -1);
      exchange.close();
      return;
    }
    if ("DELETE".equals(exchange.getRequestMethod())) {
      methods.add("DELETE");
      exchange.sendResponseHeaders(204, -1);
      exchange.close();
      return;
    }
    JsonNode body = json.readTree(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
    String method = body.path("method").asString();
    methods.add(method);
    String session = exchange.getRequestHeaders().getFirst("Mcp-Session-Id");
    if (session != null) sessions.add(session);
    if (method.startsWith("notifications/")) {
      exchange.sendResponseHeaders(202, -1);
      exchange.close();
      return;
    }
    int id = body.path("id").asInt();
    String result = switch (method) {
      case "initialize" -> "{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{}}";
      case "tools/list" -> body.path("params").has("cursor")
          ? "{\"tools\":[{\"name\":\"write_note\",\"description\":\"d\",\"inputSchema\":{\"type\":\"object\"}}]}"
          : "{\"tools\":[{\"name\":\"search\",\"description\":\"find\",\"inputSchema\":{\"type\":\"object\"},"
              + "\"annotations\":{\"readOnlyHint\":true}}],\"nextCursor\":\"p2\"}";
      case "tools/call" -> "{\"content\":[{\"type\":\"text\",\"text\":\"hello "
          + body.path("params").path("arguments").path("who").asString() + "\"}]}";
      default -> "{}";
    };
    String reply = "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":" + result + "}";
    boolean stream = "tools/call".equals(method);
    byte[] bytes = (stream
        ? "event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"}\n\n"
            + "event: message\ndata: " + reply + "\n\n"
        : reply).getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", stream ? "text/event-stream" : "application/json");
    if ("initialize".equals(method)) exchange.getResponseHeaders().add("Mcp-Session-Id", "s-1");
    exchange.sendResponseHeaders(200, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  private McpClient.Endpoint endpoint(String token) {
    return new McpClient.Endpoint(url(), McpTransport.STREAMABLE_HTTP,
        Map.of("Authorization", "Bearer " + token));
  }

  @Test
  void listsToolsAcrossPagesWithReadOnlyHintsAndKeepsTheSession() {
    List<McpClient.Tool> tools = client.listTools(endpoint("good"));

    assertThat(tools).extracting(McpClient.Tool::name).containsExactly("search", "write_note");
    assertThat(tools.get(0).readOnly()).isTrue();
    assertThat(tools.get(1).readOnly()).isFalse();
    assertThat(methods).containsSubsequence(
        "initialize", "notifications/initialized", "tools/list", "tools/list", "DELETE");
    assertThat(sessions).contains("s-1");
  }

  @Test
  void callsAToolAndReadsTheEventStreamAnswer() {
    McpClient.CallResult result = client.callTool(endpoint("good"), "search", Map.of("who", "Petri"));

    assertThat(result.text()).isEqualTo("hello Petri");
    assertThat(result.error()).isFalse();
  }

  @Test
  void aRejectedCredentialIsReportedAsAuthenticationRequired() {
    assertThatThrownBy(() -> client.listTools(endpoint("bad")))
        .isInstanceOfSatisfying(McpClient.AuthRequiredException.class,
            exception -> assertThat(exception.challenge()).contains("resource_metadata"));
  }

  @Test
  void anOversizedResponseIsRefused() {
    McpClient small = new McpClient(HttpClient.newHttpClient(), json, Duration.ofSeconds(5), 20, "test");

    assertThatThrownBy(() -> small.listTools(endpoint("good")))
        .isInstanceOf(McpClient.McpException.class);
  }
}
