package tech.wenisch.petri.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.wenisch.petri.audit.AuditLogService;
import tech.wenisch.petri.secret.SecretCipher;
import tech.wenisch.petri.security.AppUser;
import tech.wenisch.petri.security.AppUserRepository;
import tools.jackson.databind.ObjectMapper;

class McpOAuthServiceTest {
  private final List<String> tokenForms = new CopyOnWriteArrayList<>();
  private final List<String> registrations = new CopyOnWriteArrayList<>();
  private final McpServerRepository servers = mock(McpServerRepository.class);
  private final AppUserRepository users = mock(AppUserRepository.class);
  private final SecretCipher cipher = mock(SecretCipher.class);
  private HttpServer fake;
  private McpOAuthService service;
  private McpServer server;
  private final UUID owner = UUID.randomUUID();

  @BeforeEach
  void start() throws IOException {
    fake = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    fake.createContext("/", this::handle);
    fake.start();
    String base = "http://localhost:" + fake.getAddress().getPort();
    when(cipher.encrypt(any())).thenAnswer(call -> "enc:" + call.getArgument(0));
    when(cipher.decrypt(any())).thenAnswer(call -> ((String) call.getArgument(0)).substring(4));
    when(users.findByEmailIgnoreCase("u@petri.local"))
        .thenReturn(Optional.of(new AppUser(owner, "u@petri.local", "x", "USER")));
    server = new McpServer(UUID.randomUUID(), owner, Instant.now());
    server.configure("Docs", base + "/mcp", McpTransport.AUTO, McpAuthType.OAUTH, Instant.now());
    when(servers.findByIdAndOwnerId(server.id(), owner)).thenReturn(Optional.of(server));
    when(servers.findById(server.id())).thenReturn(Optional.of(server));
    service = new McpOAuthService(servers, users, cipher, mock(AuditLogService.class),
        new ObjectMapper(), HttpClient.newHttpClient(), Clock.systemUTC());
  }

  @AfterEach
  void stop() {
    fake.stop(0);
  }

  private String base() {
    return "http://localhost:" + fake.getAddress().getPort();
  }

  private void handle(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    String reply = switch (path) {
      case "/.well-known/oauth-protected-resource/mcp", "/.well-known/oauth-protected-resource" ->
          "{\"resource\":\"" + base() + "/mcp\",\"authorization_servers\":[\"" + base() + "\"]}";
      case "/.well-known/oauth-authorization-server" -> "{\"authorization_endpoint\":\"" + base()
          + "/authorize\",\"token_endpoint\":\"" + base() + "/token\",\"registration_endpoint\":\""
          + base() + "/register\"}";
      case "/register" -> {
        registrations.add(body);
        yield "{\"client_id\":\"dyn-client\"}";
      }
      case "/token" -> {
        tokenForms.add(body);
        yield body.contains("grant_type=refresh_token")
            ? "{\"access_token\":\"at-2\",\"expires_in\":3600}"
            : "{\"access_token\":\"at-1\",\"refresh_token\":\"rt-1\",\"expires_in\":3600}";
      }
      default -> null;
    };
    if (reply == null) {
      exchange.sendResponseHeaders(404, -1);
      exchange.close();
      return;
    }
    byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  @Test
  void discoversEndpointsRegistersAClientAndBuildsAPkceAuthorizationUrl() {
    var started = service.begin(server.id(), "u@petri.local", "http://petri/cb");

    assertThat(server.oauthClientId()).isEqualTo("dyn-client");
    assertThat(server.oauthDynamicClient()).isTrue();
    assertThat(registrations).singleElement().asString()
        .contains("http://petri/cb").contains("\"token_endpoint_auth_method\":\"none\"");
    String url = started.authorizationUrl();
    assertThat(url).startsWith(base() + "/authorize?response_type=code")
        .contains("client_id=dyn-client")
        .contains("code_challenge_method=S256")
        .contains("code_challenge=" + McpOAuthService.challenge(started.attempt().verifier()))
        .contains("state=" + started.attempt().state())
        .contains("resource=");
  }

  @Test
  void exchangesTheCodeWithTheVerifierAndRefreshesAnExpiredToken() {
    var started = service.begin(server.id(), "u@petri.local", "http://petri/cb");

    service.complete(started.attempt(), "the-code", "u@petri.local");

    assertThat(tokenForms.getFirst()).contains("grant_type=authorization_code")
        .contains("code=the-code").contains("code_verifier=" + started.attempt().verifier());
    assertThat(service.accessToken(server.id())).isEqualTo("at-1");

    server.oauthTokens("enc:at-1", null, Instant.now().minusSeconds(5));
    assertThat(service.accessToken(server.id())).isEqualTo("at-2");
    assertThat(tokenForms.getLast()).contains("grant_type=refresh_token").contains("refresh_token=rt-1");
    assertThat(server.oauthRefreshCiphertext()).isEqualTo("enc:rt-1");
  }

  @Test
  void anExpiredAttemptOrAnotherUsersAttemptIsRejected() {
    var started = service.begin(server.id(), "u@petri.local", "http://petri/cb");
    var expired = new McpOAuthService.Attempt(started.attempt().state(), "v", server.id(), owner,
        "http://petri/cb", Instant.now().minusSeconds(1));
    var foreign = new McpOAuthService.Attempt(started.attempt().state(), "v", server.id(),
        UUID.randomUUID(), "http://petri/cb", Instant.now().plusSeconds(60));

    assertThatThrownBy(() -> service.complete(expired, "c", "u@petri.local"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> service.complete(foreign, "c", "u@petri.local"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
