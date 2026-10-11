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
final class GithubProviderAdapter extends AbstractHttpGitProviderAdapter {
  @Autowired
  GithubProviderAdapter(ObjectMapper json) { super(json); }
  GithubProviderAdapter(ObjectMapper json, HttpClient client) { super(json, client); }

  @Override public GitProviderType type() { return GitProviderType.GITHUB; }

  @Override
  protected void authorize(HttpRequest.Builder request, String token, GitConnectionAuthMethod method) {
    request.header("Authorization", "Bearer " + token)
        .header("X-GitHub-Api-Version", "2022-11-28");
  }

  @Override
  public ProviderIdentity identity(
      GitProviderInstance instance, String token, GitConnectionAuthMethod method) {
    JsonNode node = get(instance, "/user", token, method);
    return new ProviderIdentity(node.path("id").asText(), required(node, "login"), text(node, "name"));
  }

  @Override
  public ProviderRepositoryPage repositories(
      GitProviderInstance instance, String token, GitConnectionAuthMethod method,
      int page, int pageSize, String query) {
    JsonNode nodes = get(instance,
        "/user/repos?visibility=all&affiliation=owner%2Ccollaborator%2Corganization_member"
            + "&sort=full_name&direction=asc&per_page=" + pageSize + "&page=" + page,
        token, method);
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
    return "x-access-token";
  }

  private static ProviderRepository repository(JsonNode node) {
    JsonNode permissions = node.path("permissions");
    return new ProviderRepository(
        node.path("id").asText(), required(node, "name"), required(node, "full_name"),
        required(node, "clone_url"), text(node, "html_url"), text(node, "default_branch"),
        node.path("private").asBoolean() ? "private" : "public",
        node.path("archived").asBoolean(false),
        permissions.path("push").asBoolean(false) || permissions.path("admin").asBoolean(false),
        false);
  }
}
