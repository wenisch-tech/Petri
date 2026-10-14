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
import java.util.List;
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

  protected JsonNode postJson(
      GitProviderInstance instance,
      String resource,
      String token,
      GitConnectionAuthMethod method,
      Map<String, ?> body) {
    return sendJson("POST", instance, resource, token, method, body);
  }

  protected JsonNode sendJson(
      String verb,
      GitProviderInstance instance,
      String resource,
      String token,
      GitConnectionAuthMethod method,
      Map<String, ?> body) {
    HttpRequest.Builder request = HttpRequest.newBuilder(safeUri(instance.getApiBaseUrl(), resource))
        .timeout(REQUEST_TIMEOUT)
        .header("Accept", "application/json")
        .header("Content-Type", "application/json")
        .header("User-Agent", "Petri/0.1");
    authorize(request, token, method);
    String payload = json.writeValueAsString(body);
    return send(request.method(verb, HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8)).build());
  }

  /** {@code owner/name} (or nested groups) as it may appear inside an API path. */
  protected static String repositoryPath(String fullName) {
    if (fullName == null || !fullName.matches("[A-Za-z0-9._-]+(/[A-Za-z0-9._-]+)+")
        || fullName.contains("..")) {
      throw new IllegalArgumentException("Invalid repository name");
    }
    return fullName;
  }

  protected static java.time.Instant instant(String value) {
    if (value == null || value.isBlank()) return null;
    try {
      return java.time.OffsetDateTime.parse(value).toInstant();
    } catch (java.time.format.DateTimeParseException exception) {
      return null;
    }
  }

  protected static boolean looksLikeDraft(String title) {
    if (title == null) return false;
    String value = title.strip().toLowerCase(java.util.Locale.ROOT);
    return value.startsWith("wip:") || value.startsWith("[wip]") || value.startsWith("draft:")
        || value.startsWith("[draft]");
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
        throw new GitProviderException("Provider request failed with HTTP " + response.statusCode()
            + providerMessage(body));
      }
      return json.readTree(body);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new GitProviderException("Provider request was interrupted", exception);
    } catch (IOException exception) {
      throw new GitProviderException("Provider request failed", exception);
    }
  }

  /** The provider's own explanation (e.g. "No commits between main and feature"), when it is JSON. */
  private String providerMessage(byte[] body) {
    try {
      JsonNode node = json.readTree(body);
      String message = text(node, "message");
      if (message == null || message.isBlank()) message = text(node, "error");
      if (message == null || message.isBlank()) return "";
      message = message.replaceAll("\\s+", " ").strip();
      return ": " + (message.length() > 300 ? message.substring(0, 300) + "..." : message);
    } catch (RuntimeException exception) {
      return "";
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

  /** The pull request detail of the GitHub-style API that GitHub and Forgejo both speak. */
  protected PullRequestDetail githubStyleDetail(
      GitProviderInstance instance, String token, GitConnectionAuthMethod method, String fullName,
      long number) {
    String base = "/repos/" + repositoryPath(fullName);
    JsonNode pr = get(instance, base + "/pulls/" + number, token, method);
    PullRequestSummary.State state = text(pr, "merged_at") != null || pr.path("merged").asBoolean(false)
        ? PullRequestSummary.State.MERGED
        : "open".equals(text(pr, "state")) ? PullRequestSummary.State.OPEN : PullRequestSummary.State.CLOSED;
    String title = text(pr, "title");
    PullRequestSummary summary = new PullRequestSummary(
        number, title, text(pr.path("user"), "login"), text(pr.path("head"), "ref"),
        text(pr.path("base"), "ref"), state, pr.path("draft").asBoolean(false) || looksLikeDraft(title),
        text(pr, "html_url"), instant(text(pr, "created_at")), instant(text(pr, "updated_at")));
    List<PullRequestDetail.ChangedFile> files = new java.util.ArrayList<>();
    try {
      for (JsonNode file : get(instance, base + "/pulls/" + number + "/files?per_page=100&limit=100", token, method)) {
        files.add(new PullRequestDetail.ChangedFile(
            text(file, "filename"), text(file, "previous_filename"), text(file, "status"),
            file.path("additions").asInt(0), file.path("deletions").asInt(0)));
      }
    } catch (GitProviderException exception) {
      // Older servers lack the files endpoint; the rest of the page is still useful.
    }
    List<PullRequestDetail.Comment> comments = new java.util.ArrayList<>();
    try {
      for (JsonNode comment : get(instance, base + "/issues/" + number + "/comments?per_page=100&limit=100", token, method)) {
        comments.add(new PullRequestDetail.Comment(
            text(comment.path("user"), "login"), text(comment, "body"), instant(text(comment, "created_at")),
            null, text(comment, "html_url")));
      }
    } catch (GitProviderException exception) {
      // Comments are best effort.
    }
    try {
      for (JsonNode review : get(instance, base + "/pulls/" + number + "/reviews?per_page=100&limit=100", token, method)) {
        String verdict = text(review, "state");
        String normalized = verdict == null ? "COMMENTED" : switch (verdict.toUpperCase()) {
          case "APPROVED" -> "APPROVED";
          case "CHANGES_REQUESTED", "REQUEST_CHANGES" -> "CHANGES_REQUESTED";
          default -> "COMMENTED";
        };
        String body = text(review, "body");
        // A bare "commented" review without text only carries inline comments; skip the noise.
        if ("COMMENTED".equals(normalized) && (body == null || body.isBlank())) continue;
        comments.add(new PullRequestDetail.Comment(
            text(review.path("user"), "login"), body,
            instant(text(review, "submitted_at") != null ? text(review, "submitted_at") : text(review, "created_at")),
            normalized, text(review, "html_url")));
      }
    } catch (GitProviderException exception) {
      // Reviews are best effort.
    }
    comments.sort(java.util.Comparator.comparing(
        PullRequestDetail.Comment::createdAt, java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())));
    return new PullRequestDetail(
        summary, text(pr, "body"), text(pr.path("head"), "sha"), pr.path("additions").asInt(0),
        pr.path("deletions").asInt(0), pr.path("commits").asInt(0), files, comments);
  }

  protected void githubStyleReview(
      GitProviderInstance instance, String token, GitConnectionAuthMethod method,
      String fullName, long number, String event, String markdown) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("event", event);
    if (markdown != null && !markdown.isBlank()) body.put("body", markdown);
    postJson(instance, "/repos/" + repositoryPath(fullName) + "/pulls/" + number + "/reviews",
        token, method, body);
  }

  protected void githubStyleClose(
      GitProviderInstance instance, String token, GitConnectionAuthMethod method,
      String fullName, long number) {
    sendJson("PATCH", instance, "/repos/" + repositoryPath(fullName) + "/pulls/" + number,
        token, method, Map.of("state", "closed"));
  }
}
