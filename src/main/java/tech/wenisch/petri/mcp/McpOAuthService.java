package tech.wenisch.petri.mcp;

import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.audit.AuditLogService;
import tech.wenisch.petri.config.TlsSettings;
import tech.wenisch.petri.secret.SecretCipher;
import tech.wenisch.petri.security.AppUserRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * OAuth 2.1 sign-in for MCP servers: authorization-server discovery (RFC 9728 / 8414), dynamic
 * client registration (RFC 7591), authorization code with PKCE, the {@code resource} parameter
 * (RFC 8707) and refresh. Each user signs in to their own servers; tokens are encrypted.
 */
@Service
public class McpOAuthService {
  private static final int MAX_BODY = 256 * 1024;
  private static final Duration ATTEMPT_TTL = Duration.ofMinutes(10);
  private static final Duration REFRESH_SKEW = Duration.ofSeconds(60);
  private static final SecureRandom RANDOM = new SecureRandom();

  /** A sign-in under way; kept in the user's HTTP session, keyed by its state. */
  public record Attempt(String state, String verifier, UUID serverId, UUID ownerId, String redirectUri,
      Instant expiresAt) implements Serializable {}

  private final McpServerRepository servers;
  private final AppUserRepository users;
  private final SecretCipher cipher;
  private final AuditLogService audit;
  private final ObjectMapper json;
  private final HttpClient http;
  private final Clock clock;
  private final Map<UUID, Object> locks = new ConcurrentHashMap<>();

  @Autowired
  public McpOAuthService(
      McpServerRepository servers, AppUserRepository users, SecretCipher cipher, AuditLogService audit,
      ObjectMapper json, TlsSettings tls) {
    this(servers, users, cipher, audit, json,
        tls.apply(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)).build(), Clock.systemUTC());
  }

  McpOAuthService(
      McpServerRepository servers, AppUserRepository users, SecretCipher cipher, AuditLogService audit,
      ObjectMapper json, HttpClient http, Clock clock) {
    this.servers = servers;
    this.users = users;
    this.cipher = cipher;
    this.audit = audit;
    this.json = json;
    this.http = http;
    this.clock = clock;
  }

  // ---------------------------------------------------------------------------------------------
  // Sign-in
  // ---------------------------------------------------------------------------------------------

  /** Discovers and registers as needed, and returns the attempt and the page to send the user to. */
  public record Started(Attempt attempt, String authorizationUrl) {}

  public Started begin(UUID serverId, String actorEmail, String redirectUri) {
    var actor = users.findByEmailIgnoreCase(actorEmail)
        .orElseThrow(() -> new IllegalArgumentException("Unknown user"));
    UUID ownerId = actor.getId();
    McpServer server = manageable(serverId, actor);
    if (server.authType() != McpAuthType.OAUTH) {
      throw new IllegalArgumentException("This server does not use OAuth sign-in");
    }
    discover(server);
    if (server.oauthAuthorizationUrl() == null || server.oauthTokenUrl() == null) {
      throw new IllegalStateException("Could not find the server's OAuth endpoints");
    }
    registerClientIfNeeded(server, redirectUri);
    save(server);

    String verifier = random(48);
    String state = random(24);
    Attempt attempt = new Attempt(state, verifier, server.id(), ownerId, redirectUri,
        Instant.now(clock).plus(ATTEMPT_TTL));
    StringBuilder url = new StringBuilder(server.oauthAuthorizationUrl());
    url.append(server.oauthAuthorizationUrl().contains("?") ? '&' : '?')
        .append("response_type=code")
        .append("&client_id=").append(encode(server.oauthClientId()))
        .append("&redirect_uri=").append(encode(redirectUri))
        .append("&state=").append(encode(state))
        .append("&code_challenge=").append(encode(challenge(verifier)))
        .append("&code_challenge_method=S256")
        .append("&resource=").append(encode(resource(server)));
    if (server.oauthScopes() != null) url.append("&scope=").append(encode(server.oauthScopes()));
    return new Started(attempt, url.toString());
  }

  /** Exchanges the authorization code and stores the tokens. */
  @Transactional
  public void complete(Attempt attempt, String code, String actorEmail) {
    var actor = users.findByEmailIgnoreCase(actorEmail)
        .orElseThrow(() -> new IllegalArgumentException("Unknown user"));
    UUID ownerId = actor.getId();
    if (!ownerId.equals(attempt.ownerId())) throw new IllegalArgumentException("Sign-in belongs to another user");
    if (Instant.now(clock).isAfter(attempt.expiresAt())) {
      throw new IllegalStateException("The sign-in took too long. Start again.");
    }
    McpServer server = manageable(attempt.serverId(), actor);
    Map<String, String> form = new LinkedHashMap<>();
    form.put("grant_type", "authorization_code");
    form.put("code", code);
    form.put("redirect_uri", attempt.redirectUri());
    form.put("code_verifier", attempt.verifier());
    form.put("resource", resource(server));
    store(server, tokenRequest(server, form));
    server.mark(McpServerStatus.ACTIVE, null, Instant.now(clock));
    servers.save(server);
    audit.record(actorEmail, "MCP_SERVER_SIGNED_IN", "MCP_SERVER", server.id(),
        "Signed in to MCP server " + server.name());
  }

  /** The user's own server, or an instance-wide one when the user is an administrator (shared sign-in). */
  private McpServer manageable(UUID serverId, tech.wenisch.petri.security.AppUser actor) {
    var own = servers.findByIdAndOwnerId(serverId, actor.getId());
    if (own.isPresent()) return own.get();
    if (McpServerService.isAdmin(actor)) {
      var global = servers.findByIdAndOwnerIdIsNull(serverId);
      if (global.isPresent()) return global.get();
    }
    throw new IllegalArgumentException("Unknown MCP server");
  }

  public static boolean expired(Attempt attempt, Instant now) {
    return now.isAfter(attempt.expiresAt());
  }

  // ---------------------------------------------------------------------------------------------
  // Tokens
  // ---------------------------------------------------------------------------------------------

  /**
   * The access token to send, refreshed when it is about to expire. Empty when the user has to sign
   * in again. Serialised per server, so two calls never refresh at once.
   */
  public String accessToken(UUID serverId) {
    Object lock = locks.computeIfAbsent(serverId, key -> new Object());
    synchronized (lock) {
      McpServer server = servers.findById(serverId).orElse(null);
      if (server == null || server.oauthAccessCiphertext() == null) return null;
      Instant expires = server.oauthExpiresAt();
      boolean stale = expires != null && Instant.now(clock).plus(REFRESH_SKEW).isAfter(expires);
      if (!stale) return cipher.decrypt(server.oauthAccessCiphertext());
      if (server.oauthRefreshCiphertext() == null) {
        server.clearOauthTokens();
        server.mark(McpServerStatus.NEEDS_AUTH, "The sign-in expired. Sign in again.", Instant.now(clock));
        servers.save(server);
        return null;
      }
      try {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", cipher.decrypt(server.oauthRefreshCiphertext()));
        form.put("resource", resource(server));
        store(server, tokenRequest(server, form));
        servers.save(server);
        return cipher.decrypt(server.oauthAccessCiphertext());
      } catch (RuntimeException exception) {
        server.clearOauthTokens();
        server.mark(McpServerStatus.NEEDS_AUTH, "Could not refresh the sign-in. Sign in again.",
            Instant.now(clock));
        servers.save(server);
        return null;
      }
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Discovery and registration
  // ---------------------------------------------------------------------------------------------

  private void discover(McpServer server) {
    if (server.oauthAuthorizationUrl() != null && server.oauthTokenUrl() != null) return;
    URI mcp = URI.create(server.url());
    String origin = mcp.getScheme() + "://" + mcp.getAuthority();
    String authorizationServer = origin;
    String resource = server.url();
    String path = mcp.getPath() == null ? "" : mcp.getPath();
    for (String candidate : new String[] {
        origin + "/.well-known/oauth-protected-resource" + path,
        origin + "/.well-known/oauth-protected-resource"}) {
      JsonNode metadata = getJson(candidate);
      if (metadata != null) {
        JsonNode servers = metadata.path("authorization_servers");
        if (servers.isArray() && !servers.isEmpty() && servers.get(0).isString()) {
          authorizationServer = servers.get(0).asString();
        }
        if (metadata.path("resource").isString()) resource = metadata.path("resource").asString();
        break;
      }
    }
    String as = authorizationServer.replaceAll("/+$", "");
    URI asUri = URI.create(as);
    String asOrigin = asUri.getScheme() + "://" + asUri.getAuthority();
    String asPath = asUri.getPath() == null ? "" : asUri.getPath();
    JsonNode metadata = null;
    for (String candidate : new String[] {
        asOrigin + "/.well-known/oauth-authorization-server" + asPath,
        asOrigin + "/.well-known/openid-configuration" + asPath,
        as + "/.well-known/openid-configuration"}) {
      metadata = getJson(candidate);
      if (metadata != null) break;
    }
    String authorization = as + "/authorize";
    String token = as + "/token";
    String registration = as + "/register";
    if (metadata != null) {
      authorization = text(metadata, "authorization_endpoint", authorization);
      token = text(metadata, "token_endpoint", token);
      registration = text(metadata, "registration_endpoint", null);
    }
    server.oauthEndpoints(authorization, token, registration, resource);
  }

  private void registerClientIfNeeded(McpServer server, String redirectUri) {
    boolean have = server.oauthClientId() != null && !server.oauthClientId().isBlank();
    if (have && !server.oauthDynamicClient()) return; // the user's own client
    if (server.oauthRegistrationUrl() == null) {
      if (have) return;
      throw new IllegalStateException(
          "The server does not support automatic client registration. Enter a client ID.");
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("client_name", "Petri");
    body.put("redirect_uris", java.util.List.of(redirectUri));
    body.put("grant_types", java.util.List.of("authorization_code", "refresh_token"));
    body.put("response_types", java.util.List.of("code"));
    body.put("token_endpoint_auth_method", "none");
    if (server.oauthScopes() != null) body.put("scope", server.oauthScopes());
    JsonNode reply = postJson(server.oauthRegistrationUrl(), json.writeValueAsString(body));
    String clientId = text(reply, "client_id", null);
    if (clientId == null) throw new IllegalStateException("The server did not register Petri as a client");
    String secret = text(reply, "client_secret", null);
    server.oauthClient(clientId, secret == null ? null : cipher.encrypt(secret), server.oauthScopes());
    server.oauthDynamicClient(true);
  }

  // ---------------------------------------------------------------------------------------------
  // HTTP
  // ---------------------------------------------------------------------------------------------

  private JsonNode tokenRequest(McpServer server, Map<String, String> form) {
    Map<String, String> all = new LinkedHashMap<>(form);
    all.put("client_id", server.oauthClientId());
    if (server.oauthClientSecretCiphertext() != null) {
      all.put("client_secret", cipher.decrypt(server.oauthClientSecretCiphertext()));
    }
    StringBuilder body = new StringBuilder();
    all.forEach((key, value) -> {
      if (!body.isEmpty()) body.append('&');
      body.append(encode(key)).append('=').append(encode(value));
    });
    try {
      HttpRequest request = HttpRequest.newBuilder(URI.create(server.oauthTokenUrl()))
          .timeout(Duration.ofSeconds(30))
          .header("Content-Type", "application/x-www-form-urlencoded")
          .header("Accept", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build();
      HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
      byte[] bytes;
      try (InputStream input = response.body()) {
        bytes = input.readNBytes(MAX_BODY);
      }
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new IllegalStateException("The authorization server refused the request (HTTP "
            + response.statusCode() + ")");
      }
      return json.readTree(new String(bytes, StandardCharsets.UTF_8));
    } catch (IOException exception) {
      throw new IllegalStateException("Could not reach the authorization server", exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("The sign-in was interrupted", exception);
    }
  }

  private void store(McpServer server, JsonNode token) {
    String access = text(token, "access_token", null);
    if (access == null) throw new IllegalStateException("The authorization server returned no access token");
    String refresh = text(token, "refresh_token", null);
    long seconds = token.path("expires_in").asLong(0);
    server.oauthTokens(cipher.encrypt(access), refresh == null ? null : cipher.encrypt(refresh),
        seconds > 0 ? Instant.now(clock).plusSeconds(seconds) : null);
  }

  private JsonNode getJson(String url) {
    try {
      HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10))
          .header("Accept", "application/json").GET().build();
      HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
      try (InputStream input = response.body()) {
        byte[] bytes = input.readNBytes(MAX_BODY);
        if (response.statusCode() != 200) return null;
        return json.readTree(new String(bytes, StandardCharsets.UTF_8));
      }
    } catch (IOException | RuntimeException exception) {
      return null;
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return null;
    }
  }

  private JsonNode postJson(String url, String body) {
    try {
      HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
          .header("Content-Type", "application/json").header("Accept", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(body)).build();
      HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
      try (InputStream input = response.body()) {
        byte[] bytes = input.readNBytes(MAX_BODY);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
          throw new IllegalStateException("Automatic client registration was refused (HTTP "
              + response.statusCode() + ")");
        }
        return json.readTree(new String(bytes, StandardCharsets.UTF_8));
      }
    } catch (IOException exception) {
      throw new IllegalStateException("Could not reach the registration endpoint", exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("Registration was interrupted", exception);
    }
  }

  @Transactional
  void save(McpServer server) {
    servers.save(server);
  }

  private static String resource(McpServer server) {
    return server.oauthResource() != null ? server.oauthResource() : server.url();
  }

  private static String text(JsonNode node, String field, String fallback) {
    JsonNode value = node.path(field);
    return value.isString() && !value.asString().isBlank() ? value.asString() : fallback;
  }

  private static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }

  private static String random(int bytes) {
    byte[] value = new byte[bytes];
    RANDOM.nextBytes(value);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
  }

  static String challenge(String verifier) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
