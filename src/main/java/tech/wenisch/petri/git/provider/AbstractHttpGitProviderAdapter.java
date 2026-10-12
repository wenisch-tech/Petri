package tech.wenisch.petri.git.provider;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import tech.wenisch.petri.config.TlsSettings;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

abstract class AbstractHttpGitProviderAdapter implements GitProviderAdapter {
  private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

  private final HttpClient client;
  protected final ObjectMapper json;

  AbstractHttpGitProviderAdapter(ObjectMapper json, TlsSettings tls) {
    this(json, tls.apply(HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        .followRedirects(HttpClient.Redirect.NEVER))
        .build());
  }

  AbstractHttpGitProviderAdapter(ObjectMapper json, HttpClient client) {
    this.json = json;
    this.client = client;
  }

  protected JsonNode get(
      GitProviderInstance instance,
      String resource,
      String token,
      GitConnectionAuthMethod method) {
    HttpRequest.Builder request = HttpRequest.newBuilder(safeUri(instance.getApiBaseUrl(), resource))
        .timeout(REQUEST_TIMEOUT)
        .header("Accept", "application/json")
        .header("User-Agent", "Petri/0.1");
    authorize(request, token, method);
    return send(request.GET().build());
  }

  protected JsonNode postForm(URI endpoint, Map<String, String> values) {
    validateEndpoint(endpoint);
    StringBuilder body = new StringBuilder();
    values.forEach((key, value) -> {
      if (value == null) return;
      if (!body.isEmpty()) body.append('&');
      body.append(encode(key)).append('=').append(encode(value));
    });
    HttpRequest request = HttpRequest.newBuilder(endpoint)
        .timeout(REQUEST_TIMEOUT)
        .header("Accept", "application/json")
        .header("Content-Type", "application/x-www-form-urlencoded")
        .header("User-Agent", "Petri/0.1")
        .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
        .build();
    return send(request);
  }

  protected abstract void authorize(
      HttpRequest.Builder request, String token, GitConnectionAuthMethod method);

  protected ProviderToken tokenFrom(JsonNode node) {
    String access = required(node, "access_token");
    String refresh = text(node, "refresh_token");
    Instant now = Instant.now();
    long expires = node.path("expires_in").asLong(0);
    long refreshExpires = node.path("refresh_token_expires_in").asLong(0);
    return new ProviderToken(
        access,
        refresh,
        expires > 0 ? now.plusSeconds(expires) : null,
        refreshExpires > 0 ? now.plusSeconds(refreshExpires) : null,
        text(node, "scope"));
  }

  protected Map<String, String> tokenForm(
      GitProviderInstance instance, String clientSecret, String grantType) {
    Map<String, String> form = new LinkedHashMap<>();
    form.put("client_id", instance.getOauthClientId());
    form.put("client_secret", clientSecret);
    form.put("grant_type", grantType);
    return form;
  }

  protected static URI endpoint(String value) {
    if (value == null || value.isBlank()) throw new GitProviderException("OAuth is not configured");
    URI uri = URI.create(value);
    validateEndpoint(uri);
    return uri;
  }

  protected static String required(JsonNode node, String field) {
    String value = text(node, field);
    if (value == null || value.isBlank()) {
      throw new GitProviderException("Provider response did not contain " + field);
    }
    return value;
  }

  protected static String text(JsonNode node, String field) {
    JsonNode value = node.path(field);
    return value.isMissingNode() || value.isNull() ? null : value.asText();
  }

  private JsonNode send(HttpRequest request) {
    try {
      HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
      byte[] body;
      try (InputStream input = response.body()) {
        body = input.readNBytes(MAX_RESPONSE_BYTES + 1);
      }
      if (body.length > MAX_RESPONSE_BYTES) {
        throw new GitProviderException("Provider response exceeded the size limit");
      }
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new GitProviderException("Provider request failed with HTTP " + response.statusCode());
      }
      return json.readTree(body);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new GitProviderException("Provider request was interrupted", exception);
    } catch (IOException exception) {
      throw new GitProviderException("Provider request failed", exception);
    }
  }

  private static URI safeUri(String base, String resource) {
    URI baseUri = URI.create(base);
    validateEndpoint(baseUri);
    if (resource == null || !resource.startsWith("/")) {
      throw new GitProviderException("Provider resource must be an absolute API path");
    }
    URI result = URI.create(base.replaceFirst("/+$", "") + resource);
    if (result.getUserInfo() != null || result.getFragment() != null
        || !result.normalize().equals(result)
        || !baseUri.getScheme().equalsIgnoreCase(result.getScheme())
        || !baseUri.getAuthority().equalsIgnoreCase(result.getAuthority())) {
      throw new GitProviderException("Provider resource escaped its configured API endpoint");
    }
    String basePath = baseUri.getPath().replaceFirst("/+$", "");
    if (!result.getPath().equals(basePath)
        && !result.getPath().startsWith(basePath + "/")) {
      throw new GitProviderException("Provider resource escaped its configured API path");
    }
    return result;
  }

  static void validateEndpoint(URI uri) {
    if (uri.getUserInfo() != null
        || !("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
        || uri.getHost() == null || uri.getQuery() != null || uri.getFragment() != null
        || !uri.normalize().equals(uri)) {
      throw new GitProviderException("Provider endpoints must be absolute HTTP(S) URLs without user info");
    }
  }

  protected static String encode(String value) {
    return URLEncoder.encode(value, StandardCharsets.UTF_8);
  }
}
