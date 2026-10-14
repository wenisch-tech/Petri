package tech.wenisch.petri.git.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class GitProviderAdapterTest {
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void githubParsesIdentityCatalogAndRepositoryWithoutLeakingToken() throws Exception {
    AtomicReference<HttpRequest> request = new AtomicReference<>();
    HttpClient client = client(request, value -> switch (value.uri().getPath()) {
      case "/api/user" -> ok("""
          {"id":7,"login":"octocat","name":"Octo Cat"}
          """);
      case "/api/user/repos" -> ok("""
          [{"id":42,"name":"petri","full_name":"acme/petri",
            "clone_url":"https://example.test/acme/petri.git",
            "html_url":"https://example.test/acme/petri","default_branch":"main",
            "private":true,"archived":false,"permissions":{"push":true}}]
          """);
      case "/api/repositories/42" -> ok("""
          {"id":42,"name":"petri","full_name":"acme/petri",
            "clone_url":"https://example.test/acme/petri.git","default_branch":"main",
            "private":true,"permissions":{"admin":true}}
          """);
      default -> new ResponseSpec(404, "secret-token must not escape");
    });
    GithubProviderAdapter adapter = new GithubProviderAdapter(json, client);
    GitProviderInstance instance = instance(GitProviderType.GITHUB);

    assertThat(adapter.identity(instance, "secret-token", GitConnectionAuthMethod.PAT))
        .isEqualTo(new ProviderIdentity("7", "octocat", "Octo Cat"));
    ProviderRepositoryPage page = adapter.repositories(
        instance, "secret-token", GitConnectionAuthMethod.PAT, 2, 25, "petri");
    assertThat(page.repositories()).singleElement().satisfies(repository -> {
      assertThat(repository.fullName()).isEqualTo("acme/petri");
      assertThat(repository.canWrite()).isTrue();
      assertThat(repository.visibility()).isEqualTo("private");
    });
    assertThat(request.get().uri().getRawQuery()).contains("per_page=25", "page=2");
    assertThat(request.get().headers().firstValue("Authorization"))
        .contains("Bearer secret-token");
    assertThat(adapter.repository(instance, "secret-token", GitConnectionAuthMethod.PAT, "42")
        .canWrite()).isTrue();

    assertThatThrownBy(() -> adapter.repository(
        instance, "secret-token", GitConnectionAuthMethod.PAT, "../../42"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void gitlabUsesProviderSearchAndParsesWritePermission() throws Exception {
    AtomicReference<HttpRequest> request = new AtomicReference<>();
    HttpClient client = client(request, ignored -> ok("""
        [{"id":91,"name":"service","path_with_namespace":"team/service",
          "http_url_to_repo":"https://gitlab.test/team/service.git",
          "web_url":"https://gitlab.test/team/service","default_branch":"develop",
          "visibility":"internal","archived":false,
          "permissions":{"project_access":{"access_level":30}}}]
        """));
    GitlabProviderAdapter adapter = new GitlabProviderAdapter(json, client);

    ProviderRepositoryPage page = adapter.repositories(instance(GitProviderType.GITLAB),
        "pat", GitConnectionAuthMethod.PAT, 1, 10, "service & api");

    assertThat(page.repositories()).singleElement().satisfies(repository -> {
      assertThat(repository.id()).isEqualTo("91");
      assertThat(repository.canWrite()).isTrue();
      assertThat(repository.defaultBranch()).isEqualTo("develop");
    });
    assertThat(request.get().uri().getRawQuery()).contains("membership=true")
        .contains("search=service+%26+api")
        .doesNotContain("simple=true");
  }

  @Test
  void forgejoUsesPatHeaderAndFiltersCurrentCatalogPage() throws Exception {
    AtomicReference<HttpRequest> request = new AtomicReference<>();
    HttpClient client = client(request, ignored -> ok("""
        [{"id":3,"name":"visible","full_name":"team/visible",
          "clone_url":"https://forge.test/team/visible.git","default_branch":"main",
          "private":false,"archived":true,"permissions":{"push":false}},
         {"id":4,"name":"other","full_name":"team/other",
          "clone_url":"https://forge.test/team/other.git","default_branch":"main"}]
        """));
    ForgejoProviderAdapter adapter = new ForgejoProviderAdapter(json, client);

    ProviderRepositoryPage page = adapter.repositories(instance(GitProviderType.FORGEJO),
        "forge-token", GitConnectionAuthMethod.PAT, 1, 2, "visible");

    assertThat(page.repositories()).singleElement().satisfies(repository -> {
      assertThat(repository.archived()).isTrue();
      assertThat(repository.canWrite()).isFalse();
    });
    assertThat(page.truncated()).isTrue();
    assertThat(request.get().headers().firstValue("Authorization"))
        .contains("token forge-token");
  }

  @Test
  void providerErrorsExposeOnlyStatusAndGeneratedQueriesStayOnConfiguredApiPath() throws Exception {
    HttpClient client = client(new AtomicReference<>(), ignored ->
        new ResponseSpec(401, "token=very-secret provider details"));
    GithubProviderAdapter adapter = new GithubProviderAdapter(json, client);

    assertThatThrownBy(() -> adapter.repositories(instance(GitProviderType.GITHUB),
        "very-secret", GitConnectionAuthMethod.PAT, 1, 50, null))
        .isInstanceOf(GitProviderException.class)
        .hasMessage("Provider request failed with HTTP 401")
        .hasMessageNotContaining("very-secret")
        .hasMessageNotContaining("provider details");
  }

  @Test
  void pullRequestStatesAreNormalisedOnEveryProvider() {
    AtomicReference<HttpRequest> request = new AtomicReference<>();
    GithubProviderAdapter github = new GithubProviderAdapter(json, client(request, value -> ok("""
        [{"number":1,"title":"A","state":"open","draft":false,"user":{"login":"a"},
          "head":{"ref":"f"},"base":{"ref":"main"},"created_at":"2026-05-01T10:00:00Z",
          "updated_at":"2026-05-02T10:00:00Z","html_url":"https://x/1"},
         {"number":2,"title":"B","state":"closed","merged_at":"2026-05-03T10:00:00Z",
          "head":{"ref":"g"},"base":{"ref":"main"}},
         {"number":3,"title":"C","state":"closed",
          "head":{"ref":"h"},"base":{"ref":"main"}}]
        """)));
    var pulls = github.listPullRequests(instance(GitProviderType.GITHUB), "tok",
        GitConnectionAuthMethod.PAT, "42", "acme/petri", "all");

    assertThat(request.get().uri().getQuery()).contains("state=all");
    assertThat(pulls).extracting(PullRequestSummary::state).containsExactly(
        PullRequestSummary.State.OPEN, PullRequestSummary.State.MERGED, PullRequestSummary.State.CLOSED);

    GitlabProviderAdapter gitlab = new GitlabProviderAdapter(json, client(request, value -> ok("""
        [{"iid":5,"title":"Draft: x","state":"opened","source_branch":"s","target_branch":"main",
          "author":{"username":"u"},"web_url":"https://x/5"},
         {"iid":6,"title":"y","state":"merged","source_branch":"s2","target_branch":"main"},
         {"iid":7,"title":"z","state":"closed","source_branch":"s3","target_branch":"main"}]
        """)));
    var merge = gitlab.listPullRequests(instance(GitProviderType.GITLAB), "tok",
        GitConnectionAuthMethod.PAT, "42", "acme/petri", "closed");
    assertThat(merge).extracting(PullRequestSummary::number).containsExactly(6L, 7L);

    ForgejoProviderAdapter forgejo = new ForgejoProviderAdapter(json, client(request, value -> ok("""
        [{"number":9,"title":"m","state":"closed","merged":true,
          "head":{"ref":"a"},"base":{"ref":"main"}}]
        """)));
    var merged = forgejo.listPullRequests(instance(GitProviderType.FORGEJO), "tok",
        GitConnectionAuthMethod.PAT, "42", "acme/petri", "closed");
    assertThat(merged.getFirst().state()).isEqualTo(PullRequestSummary.State.MERGED);
  }

  @Test
  void pullRequestDetailCollectsDescriptionFilesAndComments() {
    AtomicReference<HttpRequest> request = new AtomicReference<>();
    GithubProviderAdapter github = new GithubProviderAdapter(json, client(request, value -> {
      String path = value.uri().getPath();
      if (path.endsWith("/files")) {
        return ok("[{\"filename\":\"a.txt\",\"status\":\"added\",\"additions\":4,\"deletions\":0}]");
      }
      if (path.endsWith("/comments")) {
        return ok("[{\"user\":{\"login\":\"zed\"},\"body\":\"hi\",\"created_at\":\"2026-05-02T10:00:00Z\"}]");
      }
      if (path.endsWith("/reviews")) {
        return ok("[{\"user\":{\"login\":\"amy\"},\"body\":\"ok\",\"state\":\"APPROVED\",\"submitted_at\":\"2026-05-03T10:00:00Z\"}]");
      }
      return ok("{\"number\":5,\"title\":\"T\",\"body\":\"Desc\",\"state\":\"open\",\"head\":{\"ref\":\"f\",\"sha\":\"abc\"},\"base\":{\"ref\":\"main\"},\"commits\":2}");
    }));
    var detail = github.pullRequestDetail(instance(GitProviderType.GITHUB), "tok",
        GitConnectionAuthMethod.PAT, "42", "acme/petri", 5);

    assertThat(detail.description()).isEqualTo("Desc");
    assertThat(detail.headSha()).isEqualTo("abc");
    assertThat(detail.files()).singleElement().satisfies(file -> assertThat(file.path()).isEqualTo("a.txt"));
    assertThat(detail.comments()).extracting(PullRequestDetail.Comment::author).containsExactly("zed", "amy");
  }

  @Test
  void pullRequestReviewMergeAndCloseUseEachProvidersEndpoints() {
    java.util.List<String> calls = new java.util.ArrayList<>();
    AtomicReference<HttpRequest> request = new AtomicReference<>();
    var ok = (java.util.function.Function<HttpRequest, ResponseSpec>) value -> {
      calls.add(value.method() + " " + value.uri().getPath());
      return ok("{}");
    };
    GithubProviderAdapter github = new GithubProviderAdapter(json, client(request, ok));
    var gh = instance(GitProviderType.GITHUB);
    github.reviewPullRequest(gh, "t", GitConnectionAuthMethod.PAT, "1", "acme/petri", 4, true, "");
    github.mergePullRequest(gh, "t", GitConnectionAuthMethod.PAT, "1", "acme/petri", 4);
    github.closePullRequest(gh, "t", GitConnectionAuthMethod.PAT, "1", "acme/petri", 4);
    GitlabProviderAdapter gitlab = new GitlabProviderAdapter(json, client(request, ok));
    var gl = instance(GitProviderType.GITLAB);
    gitlab.reviewPullRequest(gl, "t", GitConnectionAuthMethod.PAT, "9", "t/s", 2, true, "");
    gitlab.mergePullRequest(gl, "t", GitConnectionAuthMethod.PAT, "9", "t/s", 2);
    gitlab.closePullRequest(gl, "t", GitConnectionAuthMethod.PAT, "9", "t/s", 2);

    assertThat(calls).anyMatch(c -> c.startsWith("POST ") && c.endsWith("/pulls/4/reviews"));
    assertThat(calls).anyMatch(c -> c.startsWith("PUT ") && c.endsWith("/pulls/4/merge"));
    assertThat(calls).anyMatch(c -> c.startsWith("PATCH ") && c.endsWith("/pulls/4"));
    assertThat(calls).anyMatch(c -> c.startsWith("POST ") && c.endsWith("/merge_requests/2/approve"));
    assertThat(calls).anyMatch(c -> c.startsWith("PUT ") && c.endsWith("/merge_requests/2/merge"));
    assertThat(calls).anyMatch(c -> c.startsWith("PUT ") && c.endsWith("/merge_requests/2"));
    org.junit.jupiter.api.Assertions.assertThrows(GitProviderException.class, () ->
        gitlab.reviewPullRequest(gl, "t", GitConnectionAuthMethod.PAT, "9", "t/s", 2, false, "x"));
  }

  @Test
  void pipelineRunsAreNormalisedOnEveryProvider() {
    AtomicReference<HttpRequest> request = new AtomicReference<>();
    GithubProviderAdapter github = new GithubProviderAdapter(json, client(request, value -> ok("""
        {"workflow_runs":[
          {"id":1,"name":"CI","status":"completed","conclusion":"success","head_branch":"main",
           "head_sha":"abcdef123","event":"push","html_url":"https://x/runs/1",
           "created_at":"2026-05-01T10:00:00Z","updated_at":"2026-05-01T10:05:00Z"},
          {"id":2,"name":"CI","status":"in_progress","head_branch":"f","head_sha":"b"},
          {"id":3,"name":"CI","status":"completed","conclusion":"failure","head_branch":"f","head_sha":"c"},
          {"id":4,"name":"CI","status":"queued","head_branch":"f","head_sha":"d"}]}
        """)));
    var runs = github.listPipelines(instance(GitProviderType.GITHUB), "tok",
        GitConnectionAuthMethod.PAT, "42", "acme/petri");

    assertThat(request.get().uri().getPath()).isEqualTo("/api/repos/acme/petri/actions/runs");
    assertThat(runs).extracting(PipelineRun::status)
        .containsExactly("SUCCESS", "RUNNING", "FAILED", "PENDING");
    assertThat(runs.getFirst().ref()).isEqualTo("main");

    GitlabProviderAdapter gitlab = new GitlabProviderAdapter(json, client(request, value -> ok("""
        [{"id":11,"iid":4,"status":"success","ref":"main","sha":"s","source":"push","web_url":"https://x/p/11"},
         {"id":12,"iid":5,"status":"failed","ref":"f","sha":"t"},
         {"id":13,"iid":6,"status":"canceled","ref":"f","sha":"u"},
         {"id":14,"iid":7,"status":"manual","ref":"f","sha":"v"}]
        """)));
    var pipes = gitlab.listPipelines(instance(GitProviderType.GITLAB), "tok",
        GitConnectionAuthMethod.PAT, "42", "acme/petri");
    assertThat(pipes).extracting(PipelineRun::status)
        .containsExactly("SUCCESS", "FAILED", "CANCELLED", "PENDING");
    assertThat(pipes.getFirst().name()).isEqualTo("Pipeline #4");

    ForgejoProviderAdapter forgejo = new ForgejoProviderAdapter(json, client(request, value -> ok("""
        {"workflow_runs":[{"id":21,"title":"build","status":"success","event":"push",
          "head_branch":"main","head_sha":"w","html_url":"https://x/r/21"}]}
        """)));
    var actions = forgejo.listPipelines(instance(GitProviderType.FORGEJO), "tok",
        GitConnectionAuthMethod.PAT, "42", "acme/petri");
    assertThat(actions.getFirst().status()).isEqualTo("SUCCESS");
    assertThat(actions.getFirst().name()).isEqualTo("build");
  }

  @Test
  void githubListsOpenPullRequestsAndPostsAComment() {
    AtomicReference<HttpRequest> request = new AtomicReference<>();
    HttpClient client = client(request, value -> value.method().equals("POST")
        ? new ResponseSpec(201, "{\"html_url\":\"https://example.test/acme/petri/pull/7#c1\"}")
        : ok("""
            [{"number":7,"title":"Add feature","body":"Does a thing","draft":false,
              "html_url":"https://example.test/acme/petri/pull/7","updated_at":"2026-05-01T10:00:00Z",
              "user":{"login":"alice"},
              "head":{"sha":"abc123","ref":"feature","repo":{"full_name":"fork/petri"}},
              "base":{"ref":"main","repo":{"full_name":"acme/petri"}}},
             {"number":8,"title":"WIP: later","draft":true,
              "head":{"sha":"def456","ref":"wip","repo":{"full_name":"acme/petri"}},
              "base":{"ref":"main","repo":{"full_name":"acme/petri"}}}]
            """));
    GithubProviderAdapter adapter = new GithubProviderAdapter(json, client);
    GitProviderInstance instance = instance(GitProviderType.GITHUB);

    var pulls = adapter.openPullRequests(
        instance, "tok", GitConnectionAuthMethod.PAT, "42", "acme/petri");

    assertThat(request.get().uri().getPath()).isEqualTo("/api/repos/acme/petri/pulls");
    assertThat(pulls).hasSize(2);
    assertThat(pulls.get(0)).satisfies(pull -> {
      assertThat(pull.number()).isEqualTo(7);
      assertThat(pull.headSha()).isEqualTo("abc123");
      assertThat(pull.baseRef()).isEqualTo("main");
      assertThat(pull.authorLogin()).isEqualTo("alice");
      assertThat(pull.fromFork()).isTrue();
      assertThat(pull.draft()).isFalse();
      assertThat(pull.headFetchRef()).isEqualTo("refs/pull/7/head");
      assertThat(pull.updatedAt()).isEqualTo(Instant.parse("2026-05-01T10:00:00Z"));
    });
    assertThat(pulls.get(1).draft()).isTrue();

    String url = adapter.postPullRequestComment(instance, "tok", GitConnectionAuthMethod.PAT,
        "42", "acme/petri", 7, "https://example.test/acme/petri/pull/7", "Looks fine");
    assertThat(url).endsWith("#c1");
    assertThat(request.get().method()).isEqualTo("POST");
    assertThat(request.get().uri().getPath()).isEqualTo("/api/repos/acme/petri/issues/7/comments");
    assertThatThrownBy(() -> adapter.openPullRequests(
        instance, "tok", GitConnectionAuthMethod.PAT, "42", "../../etc"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void gitlabMergeRequestsUseTheMergeRequestHeadRefAndNotes() {
    AtomicReference<HttpRequest> request = new AtomicReference<>();
    HttpClient client = client(request, value -> value.method().equals("POST")
        ? new ResponseSpec(201, "{\"id\":99}")
        : ok("""
            [{"iid":3,"title":"Fix bug","description":"Fixes it","sha":"fff000",
              "source_branch":"fix","target_branch":"develop","draft":false,
              "web_url":"https://gitlab.test/team/service/-/merge_requests/3",
              "updated_at":"2026-05-01T10:00:00.000Z","author":{"username":"bob"},
              "source_project_id":91,"target_project_id":91}]
            """));
    GitlabProviderAdapter adapter = new GitlabProviderAdapter(json, client);
    GitProviderInstance instance = instance(GitProviderType.GITLAB);

    var pulls = adapter.openPullRequests(
        instance, "tok", GitConnectionAuthMethod.PAT, "91", "team/service");

    assertThat(request.get().uri().getPath()).isEqualTo("/api/projects/91/merge_requests");
    assertThat(pulls).singleElement().satisfies(pull -> {
      assertThat(pull.number()).isEqualTo(3);
      assertThat(pull.headFetchRef()).isEqualTo("refs/merge-requests/3/head");
      assertThat(pull.fromFork()).isFalse();
      assertThat(pull.authorLogin()).isEqualTo("bob");
    });
    String url = adapter.postPullRequestComment(instance, "tok", GitConnectionAuthMethod.PAT,
        "91", "team/service", 3, pulls.get(0).webUrl(), "ok");
    assertThat(url).endsWith("/merge_requests/3#note_99");
    assertThat(request.get().uri().getPath()).isEqualTo("/api/projects/91/merge_requests/3/notes");
  }

  @Test
  void forgejoTreatsWipTitlesAsDraftsAndFailsClosedOnProviderErrors() {
    AtomicReference<HttpRequest> request = new AtomicReference<>();
    HttpClient client = client(request, value -> value.uri().getPath().endsWith("/pulls")
        ? ok("""
            [{"number":2,"title":"[WIP] thing","head":{"sha":"1","ref":"a","repo":{"full_name":"o/r"}},
              "base":{"ref":"main","repo":{"full_name":"o/r"}}}]
            """)
        : new ResponseSpec(403, "forbidden"));
    ForgejoProviderAdapter adapter = new ForgejoProviderAdapter(json, client);
    GitProviderInstance instance = instance(GitProviderType.FORGEJO);

    assertThat(adapter.openPullRequests(
        instance, "tok", GitConnectionAuthMethod.PAT, "5", "o/r")).singleElement()
        .satisfies(pull -> assertThat(pull.draft()).isTrue());
    assertThatThrownBy(() -> adapter.postPullRequestComment(instance, "tok",
        GitConnectionAuthMethod.PAT, "5", "o/r", 2, null, "x"))
        .isInstanceOf(GitProviderException.class).hasMessageContaining("403");
  }

  @Test
  void reactionsUseEachProvidersEndpointAndEmojiName() {
    AtomicReference<HttpRequest> request = new AtomicReference<>();
    HttpClient client = client(request, ignored -> new ResponseSpec(201, "{}"));

    new GithubProviderAdapter(json, client).reactToPullRequest(instance(GitProviderType.GITHUB),
        "tok", GitConnectionAuthMethod.PAT, "42", "acme/petri", 7, "eyes");
    assertThat(request.get().method()).isEqualTo("POST");
    assertThat(request.get().uri().getPath()).isEqualTo("/api/repos/acme/petri/issues/7/reactions");

    new ForgejoProviderAdapter(json, client).reactToPullRequest(instance(GitProviderType.FORGEJO),
        "tok", GitConnectionAuthMethod.PAT, "5", "o/r", 2, "eyes");
    assertThat(request.get().uri().getPath()).isEqualTo("/api/repos/o/r/issues/2/reactions");

    new GitlabProviderAdapter(json, client).reactToPullRequest(instance(GitProviderType.GITLAB),
        "tok", GitConnectionAuthMethod.PAT, "91", "team/service", 3, "eyes");
    assertThat(request.get().uri().getPath())
        .isEqualTo("/api/projects/91/merge_requests/3/award_emoji");
  }

  @Test
  void draftStateIsRecognisedOnAllProviders() {
    AtomicReference<HttpRequest> request = new AtomicReference<>();
    HttpClient github = client(request, ignored -> ok("""
        [{"number":1,"title":"Ready","draft":false,"head":{"sha":"a","ref":"x","repo":{"full_name":"o/r"}},
          "base":{"ref":"main","repo":{"full_name":"o/r"}}},
         {"number":2,"title":"Real draft","draft":true,"head":{"sha":"b","ref":"y","repo":{"full_name":"o/r"}},
          "base":{"ref":"main","repo":{"full_name":"o/r"}}}]
        """));
    assertThat(new GithubProviderAdapter(json, github).openPullRequests(
        instance(GitProviderType.GITHUB), "t", GitConnectionAuthMethod.PAT, "1", "o/r"))
        .extracting(PullRequestRef::draft).containsExactly(false, true);

    HttpClient gitlab = client(request, ignored -> ok("""
        [{"iid":1,"title":"a","sha":"a","source_branch":"x","target_branch":"main","draft":true},
         {"iid":2,"title":"b","sha":"b","source_branch":"y","target_branch":"main",
          "detailed_merge_status":"draft_status"},
         {"iid":3,"title":"Draft: c","sha":"c","source_branch":"z","target_branch":"main"},
         {"iid":4,"title":"ready","sha":"d","source_branch":"w","target_branch":"main",
          "draft":false,"detailed_merge_status":"mergeable"}]
        """));
    assertThat(new GitlabProviderAdapter(json, gitlab).openPullRequests(
        instance(GitProviderType.GITLAB), "t", GitConnectionAuthMethod.PAT, "9", "t/s"))
        .extracting(PullRequestRef::draft).containsExactly(true, true, true, false);
  }

  @Test
  void pullRequestsAreOpenedWithEachProvidersFieldsAndFollowUps() {
    AtomicReference<HttpRequest> last = new AtomicReference<>();
    java.util.List<String> calls = new java.util.ArrayList<>();
    HttpClient github = client(last, value -> {
      calls.add(value.method() + " " + value.uri().getPath());
      return value.uri().getPath().endsWith("/pulls")
          ? new ResponseSpec(201, "{\"number\":12,\"html_url\":\"https://gh.test/acme/petri/pull/12\"}")
          : new ResponseSpec(200, "[]");
    });
    var request = new CreatePullRequest("Add thing", "Body", "feature", "main", true, true,
        java.util.List.of("bug"), false, false);
    var me = new ProviderIdentity("7", "octocat", "Octo");

    CreatedPullRequest created = new GithubProviderAdapter(json, github).createPullRequest(
        instance(GitProviderType.GITHUB), "tok", GitConnectionAuthMethod.PAT, "42", "acme/petri",
        me, request);

    assertThat(created.number()).isEqualTo(12);
    assertThat(created.webUrl()).endsWith("/pull/12");
    assertThat(created.warnings()).isEmpty();
    assertThat(calls).containsExactly("POST /api/repos/acme/petri/pulls",
        "POST /api/repos/acme/petri/issues/12/labels", "POST /api/repos/acme/petri/issues/12/assignees");

    HttpClient gitlab = client(last, value -> new ResponseSpec(201,
        "{\"iid\":3,\"web_url\":\"https://gl.test/team/service/-/merge_requests/3\"}"));
    CreatedPullRequest merge = new GitlabProviderAdapter(json, gitlab).createPullRequest(
        instance(GitProviderType.GITLAB), "tok", GitConnectionAuthMethod.PAT, "91", "team/service",
        me, new CreatePullRequest("Fix", "Body", "fix", "develop", true, true, java.util.List.of(),
            true, true));
    assertThat(merge.number()).isEqualTo(3);
    assertThat(last.get().uri().getPath()).isEqualTo("/api/projects/91/merge_requests");

    HttpClient forgejo = client(last, value -> value.method().equals("GET")
        ? ok("[{\"id\":5,\"name\":\"bug\"}]")
        : value.uri().getPath().endsWith("/pulls")
            ? new ResponseSpec(201, "{\"number\":2,\"html_url\":\"https://fj.test/o/r/pulls/2\"}")
            : new ResponseSpec(200, "[]"));
    CreatedPullRequest pr = new ForgejoProviderAdapter(json, forgejo).createPullRequest(
        instance(GitProviderType.FORGEJO), "tok", GitConnectionAuthMethod.PAT, "5", "o/r", me,
        new CreatePullRequest("Do it", "B", "a", "main", true, false,
            java.util.List.of("bug", "ghost"), false, false));
    assertThat(pr.number()).isEqualTo(2);
    assertThat(pr.warnings()).anyMatch(value -> value.contains("ghost"));
  }

  @Test
  void aProviderErrorMessageIsPassedOnToTheUser() {
    HttpClient client = client(new AtomicReference<>(), ignored -> new ResponseSpec(422,
        "{\"message\":\"Validation Failed: No commits between main and feature\"}"));

    assertThatThrownBy(() -> new GithubProviderAdapter(json, client).createPullRequest(
        instance(GitProviderType.GITHUB), "tok", GitConnectionAuthMethod.PAT, "42", "acme/petri",
        null, new CreatePullRequest("T", "B", "feature", "main", false, false, null, false, false)))
        .isInstanceOf(GitProviderException.class)
        .hasMessageContaining("422").hasMessageContaining("No commits between main and feature");
  }

  private static GitProviderInstance instance(GitProviderType type) {
    return new GitProviderInstance(UUID.randomUUID(), type, type.name(),
        "https://git.example.test", "https://git.example.test/api",
        "https://git.example.test/oauth/authorize", "https://git.example.test/oauth/token",
        "repo", true, Instant.now());
  }

  private static HttpClient client(
      AtomicReference<HttpRequest> captured,
      Function<HttpRequest, ResponseSpec> responder) {
    return new StubHttpClient(captured, responder);
  }

  private static ResponseSpec ok(String body) { return new ResponseSpec(200, body); }

  private record ResponseSpec(int status, String body) {}

  private static final class StubHttpClient extends HttpClient {
    private final AtomicReference<HttpRequest> captured;
    private final Function<HttpRequest, ResponseSpec> responder;

    private StubHttpClient(
        AtomicReference<HttpRequest> captured, Function<HttpRequest, ResponseSpec> responder) {
      this.captured = captured;
      this.responder = responder;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
        throws IOException, InterruptedException {
      captured.set(request);
      ResponseSpec spec = responder.apply(request);
      return (HttpResponse<T>) new StubResponse(request, spec);
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request, HttpResponse.BodyHandler<T> handler) {
      throw new UnsupportedOperationException();
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
        HttpRequest request, HttpResponse.BodyHandler<T> handler,
        HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
      throw new UnsupportedOperationException();
    }

    @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
    @Override public Optional<Duration> connectTimeout() { return Optional.of(Duration.ofSeconds(1)); }
    @Override public Redirect followRedirects() { return Redirect.NEVER; }
    @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
    @Override public SSLContext sslContext() { return defaultSslContext(); }
    @Override public SSLParameters sslParameters() { return new SSLParameters(); }
    @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
    @Override public Version version() { return Version.HTTP_1_1; }
    @Override public Optional<Executor> executor() { return Optional.empty(); }

    private static SSLContext defaultSslContext() {
      try {
        return SSLContext.getDefault();
      } catch (java.security.NoSuchAlgorithmException exception) {
        throw new IllegalStateException(exception);
      }
    }
  }

  private static final class StubResponse implements HttpResponse<InputStream> {
    private final HttpRequest request;
    private final ResponseSpec spec;

    private StubResponse(HttpRequest request, ResponseSpec spec) {
      this.request = request;
      this.spec = spec;
    }

    @Override public int statusCode() { return spec.status(); }
    @Override public HttpRequest request() { return request; }
    @Override public Optional<HttpResponse<InputStream>> previousResponse() { return Optional.empty(); }
    @Override public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (a, b) -> true); }
    @Override public InputStream body() {
      return new ByteArrayInputStream(spec.body().getBytes(StandardCharsets.UTF_8));
    }
    @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
    @Override public URI uri() { return request.uri(); }
    @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
  }
}
