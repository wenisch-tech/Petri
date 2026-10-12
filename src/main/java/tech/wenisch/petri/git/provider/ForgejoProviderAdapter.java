package tech.wenisch.petri.git.provider;

import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tech.wenisch.petri.config.TlsSettings;
import tools.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import org.springframework.beans.factory.annotation.Autowired;

@Component
final class ForgejoProviderAdapter extends AbstractHttpGitProviderAdapter {
  @Autowired
  ForgejoProviderAdapter(ObjectMapper json, TlsSettings tls) { super(json, tls); }
  ForgejoProviderAdapter(ObjectMapper json, HttpClient client) { super(json, client); }

  @Override public GitProviderType type() { return GitProviderType.FORGEJO; }

  @Override
  protected void authorize(HttpRequest.Builder request, String token, GitConnectionAuthMethod method) {
    request.header("Authorization", (method == GitConnectionAuthMethod.PAT ? "token " : "Bearer ") + token);
  }

  @Override
  public ProviderIdentity identity(
      GitProviderInstance instance, String token, GitConnectionAuthMethod method) {
    JsonNode node = get(instance, "/user", token, method);
    return new ProviderIdentity(node.path("id").asText(), required(node, "login"), text(node, "full_name"));
  }

  @Override
  public ProviderRepositoryPage repositories(
      GitProviderInstance instance, String token, GitConnectionAuthMethod method,
      int page, int pageSize, String query) {
    JsonNode nodes = get(instance, "/user/repos?limit=" + pageSize + "&page=" + page, token, method);
    List<ProviderRepository> result = new ArrayList<>();
    for (JsonNode node : nodes) {
      ProviderRepository repository = repository(node);
      if (query == null || query.isBlank()
          || repository.fullName().toLowerCase().contains(query.toLowerCase())) result.add(repository);
    }
    return new ProviderRepositoryPage(result, page, pageSize, nodes.size() == pageSize,
        query != null && !query.isBlank() && nodes.size() == pageSize);
  }

  @Override
  public ProviderRepository repository(
      GitProviderInstance instance, String token, GitConnectionAuthMethod method, String id) {
    if (!id.matches("[0-9]+")) throw new IllegalArgumentException("Invalid repository id");
    return repository(get(instance, "/repositories/" + id, token, method));
  }

  @Override
  public List<PullRequestRef> openPullRequests(
      GitProviderInstance instance, String token, GitConnectionAuthMethod method,
      String repositoryId, String fullName) {
    JsonNode nodes = get(instance,
        "/repos/" + repositoryPath(fullName) + "/pulls?state=open&sort=recentupdate&limit=50",
        token, method);
    List<PullRequestRef> result = new ArrayList<>();
    for (JsonNode node : nodes) {
      JsonNode head = node.path("head");
      String sha = text(head, "sha");
      String headRef = text(head, "ref");
      String baseRef = text(node.path("base"), "ref");
      if (sha == null || headRef == null || baseRef == null) continue;
      String headRepository = text(head.path("repo"), "full_name");
      String baseRepository = text(node.path("base").path("repo"), "full_name");
      String title = text(node, "title");
      long number = node.path("number").asLong();
      result.add(new PullRequestRef(
          number, title, text(node, "body"), text(node.path("user"), "login"), sha, headRef, baseRef,
          "refs/pull/" + number + "/head",
          node.path("draft").asBoolean(false) || looksLikeDraft(title),
          headRepository == null || !headRepository.equalsIgnoreCase(baseRepository),
          text(node, "html_url"), instant(text(node, "updated_at"))));
    }
    return result;
  }

  @Override
  public String postPullRequestComment(
      GitProviderInstance instance, String token, GitConnectionAuthMethod method,
      String repositoryId, String fullName, long number, String webUrl, String markdown) {
    JsonNode created = postJson(instance,
        "/repos/" + repositoryPath(fullName) + "/issues/" + number + "/comments",
        token, method, Map.of("body", markdown));
    String url = text(created, "html_url");
    return url != null ? url : webUrl;
  }

  @Override
  public void reactToPullRequest(
      GitProviderInstance instance, String token, GitConnectionAuthMethod method,
      String repositoryId, String fullName, long number, String reaction) {
    // Pull requests are issues for the reactions API; repeating a reaction is a no-op.
    postJson(instance, "/repos/" + repositoryPath(fullName) + "/issues/" + number + "/reactions",
        token, method, Map.of("content", reaction));
  }

  @Override
  public CreatedPullRequest createPullRequest(
      GitProviderInstance instance, String token, GitConnectionAuthMethod method,
      String repositoryId, String fullName, ProviderIdentity me, CreatePullRequest request) {
    String path = repositoryPath(fullName);
    String title = request.draft() && !looksLikeDraft(request.title())
        ? "WIP: " + request.title() : request.title();
    Map<String, Object> body = new java.util.LinkedHashMap<>();
    body.put("title", title);
    body.put("body", request.body() == null ? "" : request.body());
    body.put("head", request.head());
    body.put("base", request.base());
    if (request.assignToMe() && me != null && me.login() != null) {
      body.put("assignees", List.of(me.login()));
    }
    JsonNode created = postJson(instance, "/repos/" + path + "/pulls", token, method, body);
    long number = created.path("number").asLong();
    if (number <= 0) throw new GitProviderException("Provider response did not contain the pull request number");
    List<String> warnings = new ArrayList<>();
    if (!request.labels().isEmpty()) {
      try {
        // Forgejo takes label ids; resolve the names the author typed.
        JsonNode known = get(instance, "/repos/" + path + "/labels?limit=100", token, method);
        List<Long> ids = new ArrayList<>();
        for (String name : request.labels()) {
          Long id = null;
          for (JsonNode label : known) {
            if (name.equalsIgnoreCase(text(label, "name"))) id = label.path("id").asLong();
          }
          if (id == null) warnings.add("Label \"" + name + "\" does not exist in the repository");
          else ids.add(id);
        }
        if (!ids.isEmpty()) {
          postJson(instance, "/repos/" + path + "/issues/" + number + "/labels", token, method,
              Map.of("labels", ids));
        }
      } catch (GitProviderException exception) {
        warnings.add("Labels could not be added: " + exception.getMessage());
      }
    }
    return new CreatedPullRequest(number, text(created, "html_url"), warnings);
  }

  @Override
  public ProviderToken exchangeCode(
      GitProviderInstance instance, String clientSecret, String code,
      String redirectUri, String codeVerifier) {
    Map<String, String> form = tokenForm(instance, clientSecret, "authorization_code");
    form.put("code", code);
    form.put("redirect_uri", redirectUri);
    form.put("code_verifier", codeVerifier);
    return tokenFrom(postForm(endpoint(instance.getOauthTokenUrl()), form));
  }

  @Override
  public ProviderToken refresh(
      GitProviderInstance instance, String clientSecret, String refreshToken) {
    Map<String, String> form = tokenForm(instance, clientSecret, "refresh_token");
    form.put("refresh_token", refreshToken);
    return tokenFrom(postForm(endpoint(instance.getOauthTokenUrl()), form));
  }

  private static ProviderRepository repository(JsonNode node) {
    return new ProviderRepository(
        node.path("id").asText(), required(node, "name"), required(node, "full_name"),
        required(node, "clone_url"), text(node, "html_url"), text(node, "default_branch"),
        node.path("private").asBoolean() ? "private" : "public",
        node.path("archived").asBoolean(false),
        node.path("permissions").path("push").asBoolean(false), false);
  }
}
