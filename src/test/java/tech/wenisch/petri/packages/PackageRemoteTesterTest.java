package tech.wenisch.petri.packages;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PackageRemoteTesterTest {
  private final PackageRemoteTester tester = new PackageRemoteTester();
  private final AtomicReference<String> authorization = new AtomicReference<>();
  private HttpServer server;

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/ok/", exchange -> {
      authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
      exchange.sendResponseHeaders(authorization.get() == null ? 401 : 200, -1);
      exchange.close();
    });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  private PackageToolConfig.Remote remote(String path, String user, String password) {
    return new PackageToolConfig.Remote(PackageEcosystem.PYPI,
        "http://127.0.0.1:" + server.getAddress().getPort() + path, PackageRemoteMode.REPLACE,
        user, password, null, false);
  }

  @Test
  void credentialsAreSentAndAnAnsweringRemoteIsOk() {
    var result = tester.test(remote("/ok", "bot", "secret"), PackageToolConfig.Trust.NONE);

    assertThat(result.ok()).isTrue();
    assertThat(authorization.get()).startsWith("Basic ");
    assertThat(result.message()).doesNotContain("secret");
  }

  @Test
  void missingCredentialsAndWrongPathsAreExplained() {
    var unauthorized = tester.test(remote("/ok", null, null), PackageToolConfig.Trust.NONE);
    var missing = tester.test(remote("/nope", null, null), PackageToolConfig.Trust.NONE);

    assertThat(unauthorized.ok()).isFalse();
    assertThat(unauthorized.message()).contains("401", "credentials");
    assertThat(missing.message()).contains("404", "base URL");
  }

  @Test
  void anUnreachableHostIsReportedWithoutAStackTrace() {
    var result = tester.test(new PackageToolConfig.Remote(PackageEcosystem.GO,
        "http://nonexistent.invalid", PackageRemoteMode.REPLACE, null, null, null, false),
        PackageToolConfig.Trust.NONE);

    assertThat(result.ok()).isFalse();
    assertThat(result.message()).contains("nonexistent.invalid");
  }
}
