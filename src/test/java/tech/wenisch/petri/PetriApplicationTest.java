package tech.wenisch.petri;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class PetriApplicationTest {
  @LocalServerPort int port;

  @Test
  void exposesHealthWithoutAuthentication() throws Exception {
    HttpResponse<String> response = get("/actuator/health");
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("\"status\":\"UP\"");
  }

  @Test
  void redirectsProtectedPagesToLogin() throws Exception {
    HttpResponse<String> response = get("/");
    assertThat(response.statusCode()).isEqualTo(HttpURLConnection.HTTP_MOVED_TEMP);
    assertThat(response.headers().firstValue("location")).hasValue("http://localhost:" + port + "/login");
  }

  @Test
  void rendersLoginPage() throws Exception {
    HttpResponse<String> response = get("/login");
    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).contains("Sign in to Petri");
  }

  private HttpResponse<String> get(String path) throws Exception {
    HttpClient client = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .build();
    HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
    return client.send(request, HttpResponse.BodyHandlers.ofString());
  }
}

