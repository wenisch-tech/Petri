package tech.wenisch.petri.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TlsSettingsTest {
  private HttpsServer server;

  @BeforeEach
  void startSelfSignedServer(@TempDir Path dir) throws Exception {
    Path keystore = dir.resolve("server.p12");
    Process keytool = new ProcessBuilder(
        Path.of(System.getProperty("java.home"), "bin", "keytool").toString(),
        "-genkeypair", "-alias", "s", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
        "-dname", "CN=not-localhost.invalid", "-storetype", "PKCS12",
        "-keystore", keystore.toString(), "-storepass", "changeit")
        .redirectErrorStream(true).start();
    assertThat(keytool.waitFor(30, TimeUnit.SECONDS)).isTrue();
    KeyStore store = KeyStore.getInstance("PKCS12");
    try (var in = Files.newInputStream(keystore)) { store.load(in, "changeit".toCharArray()); }
    KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    keys.init(store, "changeit".toCharArray());
    SSLContext context = SSLContext.getInstance("TLS");
    context.init(keys.getKeyManagers(), null, null);
    server = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.setHttpsConfigurator(new HttpsConfigurator(context));
    server.createContext("/", exchange -> {
      byte[] body = "ok".getBytes();
      exchange.sendResponseHeaders(200, body.length);
      exchange.getResponseBody().write(body);
      exchange.close();
    });
    server.start();
  }

  @AfterEach
  void stop() { server.stop(0); }

  @Test
  void verificationIsKeptByDefault() {
    HttpClient client = TlsSettings.verifying().apply(HttpClient.newBuilder()).build();

    assertThatThrownBy(() -> client.send(request(), HttpResponse.BodyHandlers.ofString()))
        .isInstanceOf(javax.net.ssl.SSLException.class);
  }

  @Test
  void insecureBuilderAcceptsSelfSignedCertificateWithMismatchedHostname() throws Exception {
    HttpClient client = TlsSettings.insecure(HttpClient.newBuilder()).build();

    assertThat(client.send(request(), HttpResponse.BodyHandlers.ofString()).body()).isEqualTo("ok");
  }

  private HttpRequest request() {
    return HttpRequest.newBuilder(
        URI.create("https://127.0.0.1:" + server.getAddress().getPort() + "/")).build();
  }
}
