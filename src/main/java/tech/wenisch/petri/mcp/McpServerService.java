package tech.wenisch.petri.mcp;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.audit.AuditLogService;
import tech.wenisch.petri.secret.SecretCipher;
import tech.wenisch.petri.security.AppUser;
import tech.wenisch.petri.security.AppUserRepository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** A user's MCP servers: configuration, credentials and the tools each server offers. */
@Service
public class McpServerService {
  static final String MASK = "********";
  static final int MAX_OUTPUT = 65_536;
  private static final String TARGET_TYPE = "MCP_SERVER";
  private static final int MAX_SECRET = 8000;
  private static final int MAX_HEADERS = 20;
  private static final Pattern HEADER_NAME = Pattern.compile("[A-Za-z0-9-]{1,64}");
  private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9 ._-]{0,79}");
  private static final Set<String> RESERVED_HEADERS = Set.of(
      "host", "content-length", "content-type", "accept", "connection", "transfer-encoding", "upgrade",
      "expect", "te", "trailer", "mcp-session-id", "mcp-protocol-version");

  public record ToolInfo(String name, String description, boolean readOnly) {}

  /** What the pages may know about a server; never a secret. */
  public record ServerView(
      UUID id, String name, String url, McpTransport transport, McpAuthType authType,
      McpServerStatus status, String lastError, boolean hasSecret, boolean signedIn,
      String oauthClientId, String oauthScopes, boolean hasClientSecret, List<ToolInfo> tools,
      Instant toolsRefreshedAt, boolean insecure, String maskedHeaders,
      boolean global, boolean credentialRequired, boolean hasPersonalCredential) {}

  public record SaveCommand(
      String name, String url, McpTransport transport, McpAuthType authType, String secret,
      String oauthClientId, String oauthClientSecret, String oauthScopes) {}

  private final McpServerRepository servers;
  private final McpUserCredentialRepository credentials;
  private final AppUserRepository users;
  private final SecretCipher cipher;
  private final McpClient client;
  private final McpOAuthService oauth;
  private final AuditLogService audit;
  private final ObjectMapper json;
  private final Clock clock = Clock.systemUTC();

  public McpServerService(
      McpServerRepository servers, McpUserCredentialRepository credentials, AppUserRepository users,
      SecretCipher cipher, McpClient client, McpOAuthService oauth, AuditLogService audit, ObjectMapper json) {
    this.servers = servers;
    this.credentials = credentials;
    this.users = users;
    this.cipher = cipher;
    this.client = client;
    this.oauth = oauth;
    this.audit = audit;
    this.json = json;
  }

  // ---------------------------------------------------------------------------------------------
  // Reading
  // ---------------------------------------------------------------------------------------------

  @Transactional(readOnly = true)
  public List<ServerView> list(String actorEmail) {
    AppUser actor = actor(actorEmail);
    List<McpServer> all = new ArrayList<>(servers.findByOwnerIdIsNullOrderByNameAsc());
    all.addAll(servers.findByOwnerIdOrderByNameAsc(actor.getId()));
    return all.stream().map(server -> view(server, actor.getId())).toList();
  }

  /** The instance-wide servers, for the administrator's page. */
  @Transactional(readOnly = true)
  public List<ServerView> listGlobal(String actorEmail) {
    AppUser actor = requireAdmin(actor(actorEmail));
    return servers.findByOwnerIdIsNullOrderByNameAsc().stream()
        .map(server -> view(server, actor.getId())).toList();
  }

  @Transactional(readOnly = true)
  public ServerView get(UUID id, String actorEmail) {
    AppUser actor = actor(actorEmail);
    return view(manageable(id, actor), actor.getId());
  }

  // ---------------------------------------------------------------------------------------------
  // Managing
  // ---------------------------------------------------------------------------------------------

  /** Creates (id null) or updates a server. An empty or fully masked secret keeps the stored one. */
  @Transactional
  public ServerView save(UUID id, SaveCommand command, String actorEmail) {
    return save(id, command, actorEmail, false);
  }

  /**
   * Creates or updates an instance-wide server (administrators only). Without a stored token or
   * headers every user adds their own credential.
   */
  @Transactional
  public ServerView saveGlobal(UUID id, SaveCommand command, String actorEmail) {
    return save(id, command, actorEmail, true);
  }

  private ServerView save(UUID id, SaveCommand command, String actorEmail, boolean global) {
    AppUser actor = actor(actorEmail);
    if (global) requireAdmin(actor);
    String name = validName(command.name());
    String url = normalizeUrl(command.url());
    McpAuthType auth = command.authType() == null ? McpAuthType.NONE : command.authType();
    McpTransport transport = command.transport() == null ? McpTransport.AUTO : command.transport();
    Instant now = Instant.now(clock);
    McpServer server;
    boolean created = id == null;
    if (created) {
      server = new McpServer(UUID.randomUUID(), global ? null : actor.getId(), now);
    } else {
      server = manageable(id, actor);
    }
    McpServer same = (server.global()
        ? servers.findByOwnerIdIsNullAndNameIgnoreCase(name)
        : servers.findByOwnerIdAndNameIgnoreCase(actor.getId(), name)).orElse(null);
    if (same != null && !same.id().equals(server.id())) {
      throw new IllegalArgumentException((server.global() ? "There is already an instance-wide MCP server called "
          : "You already have an MCP server called ") + name);
    }
    boolean urlChanged = !created && !url.equals(server.url());
    boolean authChanged = !created && auth != server.authType();
    server.configure(name, url, transport, auth, now);
    switch (auth) {
      case BEARER -> {
        String token = command.secret() == null ? "" : command.secret().strip();
        if (!token.isEmpty() && !MASK.equals(token)) server.secret(cipher.encrypt(bounded(token)));
        else if (authChanged) {
          server.secret(null);
          if (!server.global()) throw new IllegalArgumentException("Enter the bearer token or API key");
        } else if (server.secretCiphertext() == null && !server.global()) {
          throw new IllegalArgumentException("Enter the bearer token or API key");
        }
      }
      case HEADERS -> {
        boolean noneGiven = command.secret() == null || command.secret().isBlank();
        if (noneGiven && server.global()) {
          server.secret(null); // every user then adds their own headers
          break;
        }
        Map<String, String> headers = parseHeaders(command.secret());
        Map<String, String> current = headerValues(server);
        headers.replaceAll((header, value) ->
            MASK.equals(value) && current.containsKey(header) ? current.get(header) : value);
        if (headers.containsValue(MASK)) {
          throw new IllegalArgumentException("Enter a value for each new header");
        }
        if (headers.isEmpty()) throw new IllegalArgumentException("Add at least one header");
        server.secret(cipher.encrypt(formatHeaders(headers)));
      }
      case OAUTH -> {
        String clientId = command.oauthClientId() == null ? "" : command.oauthClientId().strip();
        String clientSecret = command.oauthClientSecret() == null ? "" : command.oauthClientSecret().strip();
        String secretCiphertext = server.oauthClientSecretCiphertext();
        if (!clientSecret.isEmpty() && !MASK.equals(clientSecret)) {
          secretCiphertext = cipher.encrypt(bounded(clientSecret));
        }
        String scopes = command.oauthScopes() == null ? null : command.oauthScopes().strip();
        server.oauthClient(clientId.isEmpty() ? null : clientId, secretCiphertext,
            scopes == null || scopes.isEmpty() ? null : scopes);
        server.secret(null);
        if (urlChanged) {
          server.oauthEndpoints(null, null, null, null);
          server.clearOauthTokens();
        }
      }
      default -> server.secret(null);
    }
    if (auth != McpAuthType.OAUTH && !created) server.clearOauthTokens();
    if (urlChanged || authChanged) {
      server.mark(McpServerStatus.ACTIVE, null, now);
      server.tools(null, now);
    }
    servers.save(server);
    audit.record(actor.getEmail(), created ? "MCP_SERVER_CREATED" : "MCP_SERVER_UPDATED", TARGET_TYPE,
        server.id(), (created ? "Added" : "Updated") + (server.global() ? " instance-wide" : "")
            + " MCP server " + name);
    return view(server, actor.getId());
  }

  @Transactional
  public void delete(UUID id, String actorEmail) {
    AppUser actor = actor(actorEmail);
    McpServer server = manageable(id, actor);
    servers.delete(server);
    audit.record(actor.getEmail(), "MCP_SERVER_REMOVED", TARGET_TYPE, id, "Removed MCP server " + server.name());
  }

  /** Connects, lists the tools and caches them. Failures are recorded on the server, not thrown. */
  public ServerView refreshTools(UUID id, String actorEmail) {
    AppUser actor;
    McpClient.Endpoint endpoint;
    synchronized (this) {
      actor = actor(actorEmail);
      endpoint = endpoint(manageable(id, actor), actor.getId());
    }
    List<McpClient.Tool> tools = null;
    McpServerStatus status = McpServerStatus.ACTIVE;
    String error = null;
    try {
      tools = client.listTools(endpoint);
    } catch (MissingCredentialException exception) {
      status = McpServerStatus.NEEDS_AUTH;
      error = "Add your own credential for this server first; it stores none for everyone.";
    } catch (McpClient.AuthRequiredException exception) {
      status = McpServerStatus.NEEDS_AUTH;
      error = "The server needs authentication. Check the credentials or sign in again.";
    } catch (McpClient.McpException exception) {
      status = McpServerStatus.ERROR;
      error = exception.getMessage();
    }
    return storeRefresh(id, actor, tools, status, error);
  }

  @Transactional
  ServerView storeRefresh(UUID id, AppUser actor, List<McpClient.Tool> tools, McpServerStatus status, String error) {
    McpServer server = manageable(id, actor);
    Instant now = Instant.now(clock);
    if (tools != null) server.tools(json.writeValueAsString(tools), now);
    server.mark(status, error, now);
    servers.save(server);
    audit.record(actor.getEmail(), "MCP_SERVER_TOOLS_REFRESHED", TARGET_TYPE, id,
        tools == null ? "Could not list tools of " + server.name()
            : "Discovered " + tools.size() + " tools of " + server.name());
    return view(server, actor.getId());
  }

  // ---------------------------------------------------------------------------------------------
  // Used by the agent loop and the project settings (same package)
  // ---------------------------------------------------------------------------------------------

  /** Connection details with credentials resolved. Never leaves the server process. */
  McpClient.Endpoint endpoint(McpServer server, UUID userId) {
    Map<String, String> headers = new LinkedHashMap<>();
    // An instance-wide server without a stored secret uses the credential of the user it runs for.
    String secret = server.secretCiphertext();
    if (secret == null && server.global()
        && (server.authType() == McpAuthType.BEARER || server.authType() == McpAuthType.HEADERS)) {
      secret = userId == null ? null : credentials.findById(new McpUserCredential.Key(server.id(), userId))
          .map(McpUserCredential::secretCiphertext).orElse(null);
      if (secret == null) throw new MissingCredentialException(server.name());
    }
    switch (server.authType()) {
      case BEARER -> {
        if (secret != null) headers.put("Authorization", "Bearer " + cipher.decrypt(secret));
      }
      case HEADERS -> {
        if (secret != null) headers.putAll(parseHeaders(cipher.decrypt(secret)));
      }
      case OAUTH -> {
        String token = oauth.accessToken(server.id());
        if (token != null) headers.put("Authorization", "Bearer " + token);
      }
      default -> { }
    }
    return new McpClient.Endpoint(server.url(), server.transport(), headers);
  }

  /**
   * Calls one tool on behalf of the server's owner. Output is capped; an authentication failure
   * marks the server so the user sees that it needs attention.
   */
  public McpClient.CallResult callServer(
      UUID serverId, UUID userId, String tool, Map<String, Object> arguments) {
    McpServer server = servers.findById(serverId)
        .orElseThrow(() -> new IllegalStateException("The MCP server no longer exists"));
    Map<String, Object> clean = new LinkedHashMap<>();
    if (arguments != null) arguments.forEach((key, value) -> { if (value != null) clean.put(key, value); });
    try {
      McpClient.CallResult result = client.callTool(endpoint(server, userId), tool, clean);
      String text = result.text();
      if (text.length() > MAX_OUTPUT) text = text.substring(0, MAX_OUTPUT) + "\n[output truncated]";
      return new McpClient.CallResult(text, result.error());
    } catch (MissingCredentialException exception) {
      throw new IllegalStateException("The MCP server " + server.name()
          + " needs your own credential. Ask the user to add it under MCP servers.");
    } catch (McpClient.AuthRequiredException exception) {
      if (!server.global() || server.secretCiphertext() != null) {
        markServer(server.id(), McpServerStatus.NEEDS_AUTH,
            "The server needs authentication. Check the credentials or sign in again.");
      }
      throw new IllegalStateException(
          "The MCP server " + server.name() + " needs authentication. Ask the user to sign in under MCP servers.");
    } catch (McpClient.McpException exception) {
      throw new IllegalStateException("The MCP server " + server.name() + " failed: " + exception.getMessage());
    }
  }

  @Transactional
  void markServer(UUID id, McpServerStatus status, String error) {
    servers.findById(id).ifPresent(server -> {
      server.mark(status, error, Instant.now(clock));
      servers.save(server);
    });
  }

  List<McpClient.Tool> cachedTools(McpServer server) {
    if (server.toolsJson() == null || server.toolsJson().isBlank()) return List.of();
    try {
      return json.readValue(server.toolsJson(), new TypeReference<List<McpClient.Tool>>() {});
    } catch (RuntimeException exception) {
      return List.of();
    }
  }

  /** Personal credentials for instance-wide servers that store no shared secret. */
  @Transactional
  public void saveCredential(UUID serverId, String secret, String actorEmail) {
    AppUser actor = actor(actorEmail);
    McpServer server = servers.findByIdAndOwnerIdIsNull(serverId)
        .orElseThrow(() -> new IllegalArgumentException("Unknown MCP server"));
    if (!credentialRequired(server)) {
      throw new IllegalArgumentException("This server does not take a personal credential");
    }
    String value = secret == null ? "" : secret.strip();
    if (value.isEmpty()) throw new IllegalArgumentException("Enter your credential");
    String stored = server.authType() == McpAuthType.HEADERS ? formatHeaders(parseHeaders(value)) : bounded(value);
    Instant now = Instant.now(clock);
    var key = new McpUserCredential.Key(serverId, actor.getId());
    McpUserCredential credential = credentials.findById(key).orElse(null);
    if (credential == null) credential = new McpUserCredential(serverId, actor.getId(), cipher.encrypt(stored), now);
    else credential.secret(cipher.encrypt(stored), now);
    credentials.save(credential);
    audit.record(actor.getEmail(), "MCP_CREDENTIAL_SAVED", TARGET_TYPE, serverId,
        "Stored a personal credential for MCP server " + server.name());
  }

  @Transactional
  public void clearCredential(UUID serverId, String actorEmail) {
    AppUser actor = actor(actorEmail);
    credentials.deleteById(new McpUserCredential.Key(serverId, actor.getId()));
    audit.record(actor.getEmail(), "MCP_CREDENTIAL_REMOVED", TARGET_TYPE, serverId,
        "Removed a personal MCP credential");
  }

  boolean hasCredential(UUID serverId, UUID userId) {
    return credentials.existsById(new McpUserCredential.Key(serverId, userId));
  }

  static boolean credentialRequired(McpServer server) {
    return server.global() && server.secretCiphertext() == null
        && (server.authType() == McpAuthType.BEARER || server.authType() == McpAuthType.HEADERS);
  }

  /** An instance-wide server stores no secret and this user has not added their own. */
  static final class MissingCredentialException extends RuntimeException {
    MissingCredentialException(String server) { super("No credential for MCP server " + server); }
  }

  private ServerView view(McpServer server, UUID viewerId) {
    List<ToolInfo> tools = cachedTools(server).stream()
        .map(tool -> new ToolInfo(tool.name(), tool.description(), tool.readOnly())).toList();
    boolean secret = server.secretCiphertext() != null;
    String masked = server.authType() == McpAuthType.HEADERS && secret
        ? String.join("\n", headerValues(server).keySet().stream().map(name -> name + ": " + MASK).toList())
        : "";
    return new ServerView(
        server.id(), server.name(), server.url(), server.transport(), server.authType(), server.status(),
        server.lastError(), secret, server.oauthAccessCiphertext() != null, server.oauthClientId(),
        server.oauthScopes(), server.oauthClientSecretCiphertext() != null, tools,
        server.toolsRefreshedAt(), server.url().startsWith("http://"), masked,
        server.global(), credentialRequired(server),
        credentialRequired(server) && viewerId != null && hasCredential(server.id(), viewerId));
  }

  private Map<String, String> headerValues(McpServer server) {
    if (server.authType() != McpAuthType.HEADERS || server.secretCiphertext() == null) return new LinkedHashMap<>();
    return parseHeaders(cipher.decrypt(server.secretCiphertext()));
  }

  static Map<String, String> parseHeaders(String text) {
    Map<String, String> headers = new LinkedHashMap<>();
    if (text == null) return headers;
    for (String raw : text.split("\\R")) {
      String line = raw.strip();
      if (line.isEmpty()) continue;
      int separator = line.indexOf(':');
      if (separator < 1) throw new IllegalArgumentException("Write each header as Name: value");
      String name = line.substring(0, separator).strip();
      String value = line.substring(separator + 1).strip();
      if (!HEADER_NAME.matcher(name).matches()) throw new IllegalArgumentException("Invalid header name " + name);
      if (RESERVED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
        throw new IllegalArgumentException("The header " + name + " is set by Petri and cannot be overridden");
      }
      if (value.isEmpty() || value.length() > 4096 || value.chars().anyMatch(c -> c < 32 && c != '\t')) {
        throw new IllegalArgumentException("Invalid value for header " + name);
      }
      headers.put(name, value);
      if (headers.size() > MAX_HEADERS) throw new IllegalArgumentException("At most " + MAX_HEADERS + " headers");
    }
    return headers;
  }

  private static String formatHeaders(Map<String, String> headers) {
    StringBuilder text = new StringBuilder();
    headers.forEach((name, value) -> text.append(name).append(": ").append(value).append('\n'));
    return text.toString();
  }

  private static String bounded(String value) {
    if (value.length() > MAX_SECRET) throw new IllegalArgumentException("The secret is too long");
    return value;
  }

  private static String validName(String value) {
    String name = value == null ? "" : value.strip();
    if (!NAME.matcher(name).matches()) {
      throw new IllegalArgumentException(
          "Use a short name of letters, digits, spaces, dots, dashes or underscores");
    }
    return name;
  }

  static String normalizeUrl(String value) {
    String text = value == null ? "" : value.strip();
    int end = text.length();
    while (end > 0 && text.charAt(end - 1) == '/') end--;
    text = text.substring(0, end);
    try {
      URI uri = new URI(text);
      if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
          || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null
          || text.length() > 2048) {
        throw new IllegalArgumentException(
            "The URL must be http(s) without credentials or a fragment");
      }
      return text;
    } catch (URISyntaxException exception) {
      throw new IllegalArgumentException("The URL is not valid", exception);
    }
  }

  /** The actor's own server, or an instance-wide one when the actor is an administrator. */
  McpServer manageable(UUID id, AppUser actor) {
    var own = servers.findByIdAndOwnerId(id, actor.getId());
    if (own.isPresent()) return own.get();
    if (isAdmin(actor)) {
      var global = servers.findByIdAndOwnerIdIsNull(id);
      if (global.isPresent()) return global.get();
    }
    throw new IllegalArgumentException("Unknown MCP server");
  }

  static boolean isAdmin(AppUser actor) {
    return "INSTANCE_ADMIN".equals(actor.getRole());
  }

  private static AppUser requireAdmin(AppUser actor) {
    if (!isAdmin(actor)) throw new IllegalArgumentException("Only an administrator can manage instance-wide MCP servers");
    return actor;
  }

  private AppUser actor(String email) {
    return users.findByEmailIgnoreCase(email)
        .orElseThrow(() -> new IllegalArgumentException("Unknown user"));
  }
}
