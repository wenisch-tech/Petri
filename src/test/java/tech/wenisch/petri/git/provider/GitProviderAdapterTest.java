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
