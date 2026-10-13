package tech.wenisch.petri.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tech.wenisch.petri.config.TlsSettings;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A small MCP client over the JDK HTTP client: JSON-RPC 2.0 on the Streamable HTTP transport, with
 * the older "HTTP + SSE" transport as a fallback. Each operation opens its own short-lived session,
 * so nothing is shared between users and nothing needs cleanup between runs.
 */
@Component
public class McpClient {
  static final String PROTOCOL_VERSION = "2025-06-18";
  private static final int MAX_TOOL_PAGES = 10;
  private static final int MAX_TOOLS = 100;

  /** Where and how to reach a server. {@code headers} already carry any credentials. */
  public record Endpoint(String url, McpTransport transport, Map<String, String> headers) {}

  /** One tool a server offers. */
  public record Tool(String name, String description, Map<String, Object> inputSchema, boolean readOnly) {}

  /** What a tool call returned, flattened to text. */
  public record CallResult(String text, boolean error) {}

  /** The server wants credentials (HTTP 401/403); {@code challenge} is its WWW-Authenticate value. */
  public static class AuthRequiredException extends RuntimeException {
    private final String challenge;

    public AuthRequiredException(String message, String challenge) {
      super(message);
      this.challenge = challenge;
    }

    public String challenge() { return challenge; }
  }

  /** Any other failure talking to a server. */
  public static class McpException extends RuntimeException {
    private final int status;

    public McpException(String message) { this(message, 0, null); }

    public McpException(String message, int status, Throwable cause) {
      super(message, cause);
      this.status = status;
    }

    public int status() { return status; }
  }

  private final HttpClient http;
  private final ObjectMapper json;
  private final Duration timeout;
  private final int maxBytes;
  private final String clientVersion;

  @Autowired
  public McpClient(
      TlsSettings tls,
      ObjectMapper json,
      @Value("${petri.mcp.call-timeout:30s}") Duration timeout,
      @Value("${petri.mcp.max-response-bytes:1048576}") int maxBytes,
      @Value("${petri.version:dev}") String version) {
    this(tls.apply(HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER)).build(), json, timeout, maxBytes, version);
  }

  McpClient(HttpClient http, ObjectMapper json, Duration timeout, int maxBytes, String version) {
    this.http = http;
    this.json = json;
    this.timeout = timeout;
    this.maxBytes = maxBytes;
    this.clientVersion = version;
  }

  // ---------------------------------------------------------------------------------------------
  // Operations
  // ---------------------------------------------------------------------------------------------

  /** Connects and lists every tool (paginated), bounded in count. */
  public List<Tool> listTools(Endpoint endpoint) {
    try (Session session = open(endpoint)) {
      List<Tool> tools = new ArrayList<>();
      String cursor = null;
      for (int page = 0; page < MAX_TOOL_PAGES; page++) {
        ObjectNode params = json.createObjectNode();
        if (cursor != null) params.put("cursor", cursor);
        JsonNode result = session.request("tools/list", params);
        for (JsonNode node : result.path("tools")) {
          String name = node.path("name").asString("");
          if (name.isBlank() || tools.size() >= MAX_TOOLS) continue;
          Map<String, Object> schema = node.path("inputSchema").isObject()
              ? json.convertValue(node.path("inputSchema"), new tools.jackson.core.type.TypeReference<Map<String, Object>>() {})
              : Map.of("type", "object");
          tools.add(new Tool(
              name, node.path("description").asString(""), schema,
              node.path("annotations").path("readOnlyHint").asBoolean(false)));
        }
        JsonNode next = result.path("nextCursor");
        if (!next.isString() || next.asString().isBlank()) break;
        cursor = next.asString();
      }
      return tools;
    }
  }

  /** Calls one tool and returns its output as text. */
  public CallResult callTool(Endpoint endpoint, String tool, Map<String, Object> arguments) {
    try (Session session = open(endpoint)) {
      ObjectNode params = json.createObjectNode();
      params.put("name", tool);
      params.set("arguments", json.valueToTree(arguments == null ? Map.of() : arguments));
      JsonNode result = session.request("tools/call", params);
      return new CallResult(flatten(result), result.path("isError").asBoolean(false));
    }
  }

  private String flatten(JsonNode result) {
    StringBuilder text = new StringBuilder();
    for (JsonNode part : result.path("content")) {
      String type = part.path("type").asString("");
      switch (type) {
        case "text" -> append(text, part.path("text").asString(""));
        case "image", "audio" -> append(text, "[" + type + " output omitted]");
        case "resource" -> {
          JsonNode resource = part.path("resource");
          append(text, resource.has("text") ? resource.path("text").asString("")
              : "[resource " + resource.path("uri").asString("") + "]");
        }
        case "resource_link" -> append(text, "[resource " + part.path("uri").asString("") + "]");
        default -> append(text, "[" + type + " output omitted]");
      }
    }
    if (text.isEmpty() && result.has("structuredContent")) {
      append(text, json.writeValueAsString(result.path("structuredContent")));
    }
    return text.toString();
  }

  private static void append(StringBuilder text, String value) {
    if (value == null || value.isEmpty()) return;
    if (!text.isEmpty()) text.append('\n');
    text.append(value);
  }

  // ---------------------------------------------------------------------------------------------
  // Sessions
  // ---------------------------------------------------------------------------------------------

  private interface Session extends AutoCloseable {
    JsonNode request(String method, ObjectNode params);

    void notifyServer(String method);

    @Override
    void close();
  }

  private Session open(Endpoint endpoint) {
    McpTransport transport = endpoint.transport() == null ? McpTransport.AUTO : endpoint.transport();
    if (transport == McpTransport.SSE) return openSse(endpoint);
    try {
      return openStreamable(endpoint);
    } catch (McpException exception) {
      // Older servers answer the POST with 404/405/400; those speak the HTTP + SSE transport.
      boolean unsupported = exception.status() == 404 || exception.status() == 405 || exception.status() == 400;
      if (transport == McpTransport.AUTO && unsupported) return openSse(endpoint);
      throw exception;
    }
  }

  private Session openStreamable(Endpoint endpoint) {
    StreamableSession session = new StreamableSession(endpoint);
    try {
      ObjectNode params = initializeParams();
      session.initialize(params);
      session.notifyServer("notifications/initialized");
      return session;
    } catch (RuntimeException exception) {
      session.close();
      throw exception;
    }
  }

  private Session openSse(Endpoint endpoint) {
    SseSession session = new SseSession(endpoint);
    try {
      session.connect();
      session.request("initialize", initializeParams());
      session.notifyServer("notifications/initialized");
      return session;
    } catch (RuntimeException exception) {
      session.close();
      throw exception;
    }
  }

  private ObjectNode initializeParams() {
    ObjectNode params = json.createObjectNode();
    params.put("protocolVersion", PROTOCOL_VERSION);
    params.set("capabilities", json.createObjectNode());
    ObjectNode info = params.putObject("clientInfo");
    info.put("name", "petri");
    info.put("version", clientVersion);
    return params;
  }

  private ObjectNode message(String method, ObjectNode params, Integer id) {
    ObjectNode message = json.createObjectNode();
    message.put("jsonrpc", "2.0");
    if (id != null) message.put("id", id);
    message.put("method", method);
    if (params != null) message.set("params", params);
    return message;
  }

  private JsonNode unwrap(JsonNode response) {
    JsonNode error = response.path("error");
    if (error.isObject()) {
      throw new McpException("The server returned an error: "
          + bounded(error.path("message").asString("unknown error")));
    }
    return response.path("result");
  }

  private HttpRequest.Builder builder(URI uri, Map<String, String> headers) {
    HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(timeout);
    headers.forEach(request::header);
    return request;
  }

  private AuthRequiredException auth(HttpResponse<?> response) {
    return new AuthRequiredException(
        "The MCP server requires authentication (HTTP " + response.statusCode() + ")",
        response.headers().firstValue("www-authenticate").orElse(null));
  }

  private static String bounded(String value) {
    String text = value == null ? "" : value.replaceAll("\\s+", " ").strip();
    return text.length() > 300 ? text.substring(0, 299) + "…" : text;
  }

  // ---- Streamable HTTP ------------------------------------------------------------------------

  private final class StreamableSession implements Session {
    private final Endpoint endpoint;
    private final AtomicInteger ids = new AtomicInteger();
    private volatile String sessionId;

    StreamableSession(Endpoint endpoint) { this.endpoint = endpoint; }

    void initialize(ObjectNode params) { request("initialize", params); }

    @Override
    public JsonNode request(String method, ObjectNode params) {
      int id = ids.incrementAndGet();
      return unwrap(post(message(method, params, id), id));
    }

    @Override
    public void notifyServer(String method) {
      post(message(method, null, null), null);
    }

    private JsonNode post(ObjectNode body, Integer id) {
      try {
        HttpRequest.Builder request = builder(URI.create(endpoint.url()), endpoint.headers())
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .header("MCP-Protocol-Version", PROTOCOL_VERSION)
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8));
        if (sessionId != null) request.header("Mcp-Session-Id", sessionId);
        HttpResponse<InputStream> response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
        response.headers().firstValue("mcp-session-id").ifPresent(value -> sessionId = value);
        int status = response.statusCode();
        try (InputStream input = response.body()) {
          if (status == 401 || status == 403) throw auth(response);
          if (status < 200 || status >= 300) {
            throw new McpException("The MCP server answered with HTTP " + status, status, null);
          }
          if (id == null) return null; // notification: 202 Accepted, nothing to read
          String type = response.headers().firstValue("content-type").orElse("").toLowerCase();
          if (type.contains("text/event-stream")) return awaitEvent(input, id);
          JsonNode parsed = json.readTree(readBounded(input));
          if (parsed.isArray()) {
            for (JsonNode element : parsed) if (element.path("id").asInt(-1) == id) return element;
            throw new McpException("The MCP server did not answer the request");
          }
          return parsed;
        }
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new McpException("The MCP request was interrupted", 0, exception);
      } catch (IOException exception) {
        throw new McpException("Could not reach the MCP server: " + bounded(exception.getMessage()), 0, exception);
      } catch (IllegalArgumentException exception) {
        throw new McpException("The MCP server URL is not valid", 0, exception);
      }
    }

    private JsonNode awaitEvent(InputStream input, int id) {
      JsonNode[] found = new JsonNode[1];
      readEvents(input, event -> {
        if (!"message".equals(event.type())) return false;
        JsonNode node = json.readTree(event.data());
        if (node.path("id").asInt(-1) == id && (node.has("result") || node.has("error"))) {
          found[0] = node;
          return true;
        }
        return false;
      });
      if (found[0] == null) throw new McpException("The MCP server closed the stream without an answer");
      return found[0];
    }

    @Override
    public void close() {
      if (sessionId == null) return;
      try {
        HttpRequest request = builder(URI.create(endpoint.url()), endpoint.headers())
            .header("Mcp-Session-Id", sessionId)
            .header("MCP-Protocol-Version", PROTOCOL_VERSION)
            .timeout(Duration.ofSeconds(5)).DELETE().build();
        http.send(request, HttpResponse.BodyHandlers.discarding());
      } catch (IOException | RuntimeException exception) {
        // Best effort: the server expires idle sessions on its own.
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
      }
    }
  }

  // ---- Legacy HTTP + SSE ----------------------------------------------------------------------

  private final class SseSession implements Session {
    private final Endpoint endpoint;
    private final AtomicInteger ids = new AtomicInteger();
    private final Map<Integer, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();
    private final CompletableFuture<URI> messages = new CompletableFuture<>();
    private volatile InputStream stream;

    SseSession(Endpoint endpoint) { this.endpoint = endpoint; }

    void connect() {
      try {
        HttpRequest request = builder(URI.create(endpoint.url()), endpoint.headers())
            .header("Accept", "text/event-stream").GET().build();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        int status = response.statusCode();
        if (status == 401 || status == 403) throw auth(response);
        if (status < 200 || status >= 300) {
          throw new McpException("The MCP server answered with HTTP " + status, status, null);
        }
        stream = response.body();
        Thread.ofVirtual().name("mcp-sse").start(this::pump);
        messages.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
        try {
          messages.get();
        } catch (ExecutionException exception) {
          throw new McpException("The MCP server did not announce a message endpoint", 0, exception);
        }
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new McpException("The MCP connection was interrupted", 0, exception);
      } catch (IOException exception) {
        throw new McpException("Could not reach the MCP server: " + bounded(exception.getMessage()), 0, exception);
      } catch (IllegalArgumentException exception) {
        throw new McpException("The MCP server URL is not valid", 0, exception);
      }
    }

    private void pump() {
      try {
        URI base = URI.create(endpoint.url());
        parseEvents(stream, event -> {
          if ("endpoint".equals(event.type())) {
            URI target = base.resolve(event.data().strip());
            // The message endpoint must stay on the server the user configured.
            if (!java.util.Objects.equals(target.getAuthority(), base.getAuthority())) {
              messages.completeExceptionally(new McpException("The MCP server pointed to another host"));
              return true;
            }
            messages.complete(target);
          } else if ("message".equals(event.type())) {
            JsonNode node = json.readTree(event.data());
            if (node.has("id")) {
              CompletableFuture<JsonNode> future = pending.remove(node.path("id").asInt(-1));
              if (future != null) future.complete(node);
            }
          }
          return false;
        });
      } catch (RuntimeException exception) {
        messages.completeExceptionally(exception);
      } finally {
        pending.values().forEach(future -> future.completeExceptionally(
            new McpException("The MCP stream ended before the server answered")));
      }
    }

    @Override
    public JsonNode request(String method, ObjectNode params) {
      int id = ids.incrementAndGet();
      CompletableFuture<JsonNode> future = new CompletableFuture<>();
      pending.put(id, future);
      send(message(method, params, id));
      try {
        return unwrap(future.get(timeout.toMillis(), TimeUnit.MILLISECONDS));
      } catch (TimeoutException exception) {
        pending.remove(id);
        throw new McpException("The MCP server did not answer in time", 0, exception);
      } catch (ExecutionException exception) {
        throw exception.getCause() instanceof McpException mcp ? mcp
            : new McpException("The MCP request failed", 0, exception);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new McpException("The MCP request was interrupted", 0, exception);
      }
    }

    @Override
    public void notifyServer(String method) {
      send(message(method, null, null));
    }

    private void send(ObjectNode body) {
      try {
        HttpRequest request = builder(messages.get(timeout.toMillis(), TimeUnit.MILLISECONDS), endpoint.headers())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8)).build();
        HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
        int status = response.statusCode();
        if (status == 401 || status == 403) throw auth(response);
        if (status < 200 || status >= 300) {
          throw new McpException("The MCP server answered with HTTP " + status, status, null);
        }
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new McpException("The MCP request was interrupted", 0, exception);
      } catch (IOException | ExecutionException | TimeoutException exception) {
        throw new McpException("Could not reach the MCP server: " + bounded(exception.getMessage()), 0, exception);
      }
    }

    @Override
    public void close() {
      try {
        if (stream != null) stream.close();
      } catch (IOException exception) {
        // Closing a stream that is already gone is fine.
      }
    }
  }

  // ---- SSE parsing ----------------------------------------------------------------------------

  private record SseEvent(String type, String data) {}

  @FunctionalInterface
  private interface EventHandler {
    boolean handle(SseEvent event) throws IOException;
  }

  /** Parses server-sent events until the handler returns true or the stream ends; no deadline. */
  private void parseEvents(InputStream input, EventHandler handler) {
    long total = 0;
    String type = "message";
    StringBuilder data = new StringBuilder();
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        total += line.length() + 1;
        if (total > maxBytes * 4L) throw new McpException("The MCP response exceeded the size limit");
        if (line.isEmpty()) {
          if (!data.isEmpty() && handler.handle(new SseEvent(type, data.toString()))) return;
          type = "message";
          data.setLength(0);
        } else if (line.startsWith("event:")) {
          type = line.substring(6).strip();
        } else if (line.startsWith("data:")) {
          if (!data.isEmpty()) data.append('\n');
          data.append(line.substring(5).stripLeading());
        }
      }
      if (!data.isEmpty()) handler.handle(new SseEvent(type, data.toString()));
    } catch (IOException exception) {
      throw new McpException("The MCP stream failed: " + bounded(exception.getMessage()), 0, exception);
    }
  }

  /** Like {@link #parseEvents} but bounded in time, for a request that must be answered. */
  private void readEvents(InputStream input, EventHandler handler) {
    CompletableFuture<Void> task = CompletableFuture.runAsync(
        () -> parseEvents(input, handler), Thread::startVirtualThread);
    try {
      task.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException exception) {
      task.cancel(true);
      try {
        input.close();
      } catch (IOException ignored) {
        // The stream is being abandoned.
      }
      throw new McpException("The MCP server did not answer in time", 0, exception);
    } catch (ExecutionException exception) {
      Throwable cause = exception.getCause();
      if (cause instanceof McpException mcp) throw mcp;
      if (cause instanceof RuntimeException runtime) {
        throw new McpException("The MCP server sent an unreadable event: " + bounded(runtime.getMessage()), 0, runtime);
      }
      throw new McpException("The MCP stream failed", 0, cause);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new McpException("The MCP request was interrupted", 0, exception);
    }
  }

  private byte[] readBounded(InputStream input) throws IOException {
    byte[] body = input.readNBytes(maxBytes + 1);
    if (body.length > maxBytes) throw new McpException("The MCP response exceeded the size limit");
    return body;
  }
}
