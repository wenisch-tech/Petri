package tech.wenisch.petri.git.provider;

import java.net.http.HttpRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import org.springframework.beans.factory.annotation.Autowired;

@Component
final class GitlabProviderAdapter extends AbstractHttpGitProviderAdapter {
  @Autowired
  GitlabProviderAdapter(ObjectMapper json) { super(json); }
  GitlabProviderAdapter(ObjectMapper json, HttpClient client) { super(json, client); }

  @Override public GitProviderType type() { return GitProviderType.GITLAB; }

  @Override
  protected void authorize(HttpRequest.Builder request, String token, GitConnectionAuthMethod method) {
    request.header("Authorization", "Bearer " + token);
  }

  @Override
  public ProviderIdentity identity(
      GitProviderInstance instance, String token, GitConnectionAuthMethod method) {
    JsonNode node = get(instance, "/user", token, method);
    return new ProviderIdentity(node.path("id").asText(), required(node, "username"), text(node, "name"));
  }

  @Override
  public ProviderRepositoryPage repositories(
      GitProviderInstance instance, String token, GitConnectionAuthMethod method,
      int page, int pageSize, String query) {
    String resource = "/projects?membership=true&order_by=path_with_namespace"
        + "&sort=asc&per_page=" + pageSize + "&page=" + page;
    if (query != null && !query.isBlank()) resource += "&search=" + encode(query.trim());
    JsonNode nodes = get(instance, resource, token, method);
    List<ProviderRepository> result = new ArrayList<>();
    for (JsonNode node : nodes) result.add(repository(node));
    return new ProviderRepositoryPage(result, page, pageSize, nodes.size() == pageSize, false);
  }

  @Override
  public ProviderRepository repository(
      GitProviderInstance instance, String token, GitConnectionAuthMethod method, String id) {
    if (!id.matches("[0-9]+")) throw new IllegalArgumentException("Invalid repository id");
    return repository(get(instance, "/projects/" + id, token, method));
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

  @Override
  public String gitUsername(GitConnectionAuthMethod method, ProviderIdentity identity) {
    return method == GitConnectionAuthMethod.OAUTH ? "oauth2" : identity.login();
  }

  private static ProviderRepository repository(JsonNode node) {
    int projectAccess = node.path("permissions").path("project_access").path("access_level").asInt(0);
    int groupAccess = node.path("permissions").path("group_access").path("access_level").asInt(0);
    return new ProviderRepository(
        node.path("id").asText(), required(node, "name"), required(node, "path_with_namespace"),
        required(node, "http_url_to_repo"), text(node, "web_url"), text(node, "default_branch"),
        text(node, "visibility"), node.path("archived").asBoolean(false),
        Math.max(projectAccess, groupAccess) >= 30, false);
  }
}
