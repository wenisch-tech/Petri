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
final class GitlabProviderAdapter extends AbstractHttpGitProviderAdapter {
  @Autowired
  GitlabProviderAdapter(ObjectMapper json, TlsSettings tls) { super(json, tls); }
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
  public List<PullRequestRef> openPullRequests(
      GitProviderInstance instance, String token, GitConnectionAuthMethod method,
      String repositoryId, String fullName) {
    if (!repositoryId.matches("[0-9]+")) throw new IllegalArgumentException("Invalid repository id");
    JsonNode nodes = get(instance,
        "/projects/" + repositoryId + "/merge_requests?state=opened&order_by=updated_at&sort=desc&per_page=50",
        token, method);
    List<PullRequestRef> result = new ArrayList<>();
    for (JsonNode node : nodes) {
      String sha = text(node, "sha");
      String headRef = text(node, "source_branch");
      String baseRef = text(node, "target_branch");
      if (sha == null || headRef == null || baseRef == null) continue;
      long number = node.path("iid").asLong();
      boolean draft = node.path("draft").asBoolean(false)
          || node.path("work_in_progress").asBoolean(false)
          || "draft_status".equals(text(node, "detailed_merge_status"))
          || looksLikeDraft(text(node, "title"));
      result.add(new PullRequestRef(
          number, text(node, "title"), text(node, "description"),
          text(node.path("author"), "username"), sha, headRef, baseRef,
          "refs/merge-requests/" + number + "/head", draft,
          node.path("source_project_id").asLong() != node.path("target_project_id").asLong(),
          text(node, "web_url"), instant(text(node, "updated_at"))));
    }
    return result;
  }

  @Override
  public List<PullRequestSummary> listPullRequests(
      GitProviderInstance instance, String token, GitConnectionAuthMethod method,
      String repositoryId, String fullName, String state) {
    if (!repositoryId.matches("[0-9]+")) throw new IllegalArgumentException("Invalid repository id");
    String query = "open".equals(state) ? "opened" : "all";
    JsonNode nodes = get(instance, "/projects/" + repositoryId + "/merge_requests?state=" + query
        + "&order_by=updated_at&sort=desc&per_page=50", token, method);
    List<PullRequestSummary> result = new ArrayList<>();
    for (JsonNode node : nodes) {
      String value = text(node, "state");
      PullRequestSummary.State mapped = "merged".equals(value) ? PullRequestSummary.State.MERGED
          : "opened".equals(value) ? PullRequestSummary.State.OPEN : PullRequestSummary.State.CLOSED;
      if ("closed".equals(state) && mapped == PullRequestSummary.State.OPEN) continue;
      boolean draft = node.path("draft").asBoolean(false)
          || node.path("work_in_progress").asBoolean(false) || looksLikeDraft(text(node, "title"));
      result.add(new PullRequestSummary(
          node.path("iid").asLong(), text(node, "title"), text(node.path("author"), "username"),
          text(node, "source_branch"), text(node, "target_branch"), mapped, draft,
          text(node, "web_url"), instant(text(node, "created_at")), instant(text(node, "updated_at"))));
    }
    return result;
  }

  @Override
  public PullRequestDetail pullRequestDetail(
      GitProviderInstance instance, String token, GitConnectionAuthMethod method,
      String repositoryId, String fullName, long number) {
    if (!repositoryId.matches("[0-9]+")) throw new IllegalArgumentException("Invalid repository id");
    String base = "/projects/" + repositoryId + "/merge_requests/" + number;
    JsonNode mr = get(instance, base, token, method);
    String value = text(mr, "state");
    PullRequestSummary.State state = "merged".equals(value) ? PullRequestSummary.State.MERGED
        : "opened".equals(value) ? PullRequestSummary.State.OPEN : PullRequestSummary.State.CLOSED;
    String title = text(mr, "title");
    PullRequestSummary summary = new PullRequestSummary(
        number, title, text(mr.path("author"), "username"), text(mr, "source_branch"),
        text(mr, "target_branch"), state,
        mr.path("draft").asBoolean(false) || mr.path("work_in_progress").asBoolean(false) || looksLikeDraft(title),
        text(mr, "web_url"), instant(text(mr, "created_at")), instant(text(mr, "updated_at")));
    List<PullRequestDetail.ChangedFile> files = new ArrayList<>();
    int additions = 0;
    int deletions = 0;
    try {
      JsonNode changes = get(instance, base + "/changes", token, method).path("changes");
      for (JsonNode change : changes) {
        int added = 0;
        int removed = 0;
        String diff = text(change, "diff");
        if (diff != null) {
          for (String line : diff.split("\n")) {
            if (line.startsWith("+") && !line.startsWith("+++")) added++;
            else if (line.startsWith("-") && !line.startsWith("---")) removed++;
          }
        }
        additions += added;
        deletions += removed;
        String status = change.path("new_file").asBoolean(false) ? "added"
            : change.path("deleted_file").asBoolean(false) ? "removed"
                : change.path("renamed_file").asBoolean(false) ? "renamed" : "modified";
        files.add(new PullRequestDetail.ChangedFile(
            text(change, "new_path"), change.path("renamed_file").asBoolean(false) ? text(change, "old_path") : null,
            status, added, removed));
      }
    } catch (GitProviderException exception) {
      // The changes endpoint may be unavailable for large merge requests.
    }
    List<PullRequestDetail.Comment> comments = new ArrayList<>();
    try {
      for (JsonNode note : get(instance, base + "/notes?sort=asc&order_by=created_at&per_page=100", token, method)) {
        if (note.path("system").asBoolean(false)) continue;
        comments.add(new PullRequestDetail.Comment(
            text(note.path("author"), "username"), text(note, "body"), instant(text(note, "created_at")),
            null, text(mr, "web_url") != null ? text(mr, "web_url") + "#note_" + text(note, "id") : null));
      }
    } catch (GitProviderException exception) {
      // Notes are best effort.
    }
    return new PullRequestDetail(
        summary, text(mr, "description"), text(mr, "sha"), additions, deletions, 0, files, comments);
  }

  @Override
  public List<PipelineRun> listPipelines(
      GitProviderInstance instance, String token, GitConnectionAuthMethod method,
      String repositoryId, String fullName) {
    if (!repositoryId.matches("[0-9]+")) throw new IllegalArgumentException("Invalid repository id");
    JsonNode nodes = get(instance, "/projects/" + repositoryId
        + "/pipelines?per_page=30&order_by=updated_at&sort=desc", token, method);
    List<PipelineRun> result = new ArrayList<>();
    for (JsonNode node : nodes) {
      String status = text(node, "status");
      String number = node.has("iid") ? text(node, "iid") : text(node, "id");
      result.add(new PipelineRun(
          text(node, "id"), "Pipeline #" + number, PipelineStatus.gitlab(status), status,
          text(node, "ref"), text(node, "sha"), text(node, "source"), text(node, "web_url"),
          instant(text(node, "created_at")), instant(text(node, "updated_at"))));
    }
    return result;
  }

  @Override
  public String postPullRequestComment(
      GitProviderInstance instance, String token, GitConnectionAuthMethod method,
      String repositoryId, String fullName, long number, String webUrl, String markdown) {
    if (!repositoryId.matches("[0-9]+")) throw new IllegalArgumentException("Invalid repository id");
    JsonNode created = postJson(instance,
        "/projects/" + repositoryId + "/merge_requests/" + number + "/notes",
        token, method, Map.of("body", markdown));
    String id = text(created, "id");
    return webUrl != null && id != null ? webUrl + "#note_" + id : webUrl;
  }

  @Override
  public void reviewPullRequest(
      GitProviderInstance instance, String token, GitConnectionAuthMethod method,
      String repositoryId, String fullName, long number, boolean approve, String markdown) {
    if (!approve) {
      throw new GitProviderException(
          "GitLab has no 'request changes' verdict; leave a comment instead");
    }
    if (!repositoryId.matches("[0-9]+")) throw new IllegalArgumentException("Invalid repository id");
    postJson(instance, "/projects/" + repositoryId + "/merge_requests/" + number + "/approve",
        token, method, Map.of());
  }

  @Override
  public void mergePullRequest(
      GitProviderInstance instance, String token, GitConnectionAuthMethod method,
      String repositoryId, String fullName, long number) {
    if (!repositoryId.matches("[0-9]+")) throw new IllegalArgumentException("Invalid repository id");
    sendJson("PUT", instance, "/projects/" + repositoryId + "/merge_requests/" + number + "/merge",
        token, method, Map.of());
  }

  @Override
  public void closePullRequest(
      GitProviderInstance instance, String token, GitConnectionAuthMethod method,
      String repositoryId, String fullName, long number) {
    if (!repositoryId.matches("[0-9]+")) throw new IllegalArgumentException("Invalid repository id");
    sendJson("PUT", instance, "/projects/" + repositoryId + "/merge_requests/" + number,
        token, method, Map.of("state_event", "close"));
  }

  @Override
  public void reactToPullRequest(
      GitProviderInstance instance, String token, GitConnectionAuthMethod method,
      String repositoryId, String fullName, long number, String reaction) {
    if (!repositoryId.matches("[0-9]+")) throw new IllegalArgumentException("Invalid repository id");
    postJson(instance, "/projects/" + repositoryId + "/merge_requests/" + number + "/award_emoji",
        token, method, Map.of("name", reaction));
  }

  @Override
  public CreatedPullRequest createPullRequest(
      GitProviderInstance instance, String token, GitConnectionAuthMethod method,
      String repositoryId, String fullName, ProviderIdentity me, CreatePullRequest request) {
    if (!repositoryId.matches("[0-9]+")) throw new IllegalArgumentException("Invalid repository id");
    String title = request.draft() && !looksLikeDraft(request.title())
        ? "Draft: " + request.title() : request.title();
    Map<String, Object> body = new java.util.LinkedHashMap<>();
    body.put("source_branch", request.head());
    body.put("target_branch", request.base());
    body.put("title", title);
    body.put("description", request.body() == null ? "" : request.body());
    body.put("remove_source_branch", request.removeSourceBranch());
    body.put("squash", request.squash());
    if (!request.labels().isEmpty()) body.put("labels", String.join(",", request.labels()));
    List<String> warnings = new ArrayList<>();
    if (request.assignToMe() && me != null && me.id() != null && me.id().matches("[0-9]+")) {
      body.put("assignee_ids", List.of(Long.parseLong(me.id())));
    } else if (request.assignToMe()) {
      warnings.add("The merge request could not be assigned to you: your GitLab user id is unknown");
    }
    JsonNode created = postJson(instance, "/projects/" + repositoryId + "/merge_requests",
        token, method, body);
    long number = created.path("iid").asLong();
    if (number <= 0) throw new GitProviderException("Provider response did not contain the merge request number");
    return new CreatedPullRequest(number, text(created, "web_url"), warnings);
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
