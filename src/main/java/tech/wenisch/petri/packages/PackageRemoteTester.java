package tech.wenisch.petri.packages;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Base64;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import org.springframework.stereotype.Component;

/**
 * Checks that a remote answers: one bounded GET of a well-known path with its credentials and the
 * configured trust. It reports a verdict and never the response body.
 */
@Component
public class PackageRemoteTester {
  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  /** @param ok whether the remote is usable as configured */
  public record Result(boolean ok, String message) {}

  public Result test(PackageToolConfig.Remote remote, PackageToolConfig.Trust trust) {
    URI uri;
    try {
      uri = URI.create(probe(remote));
    } catch (IllegalArgumentException exception) {
      return new Result(false, "The URL is not valid.");
    }
    boolean skipVerification = remote.insecure() || trust.ignoreTlsErrors();
    try {
      HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT)
          .followRedirects(HttpClient.Redirect.NEVER).sslContext(context(trust, skipVerification))
          .build();
      HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(TIMEOUT).GET()
          .header("User-Agent", "petri-package-check");
      if (remote.hasCredentials()) {
        request.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
            (remote.username() + ":" + remote.password()).getBytes(StandardCharsets.UTF_8)));
      }
      HttpResponse<InputStream> response = client.send(
          request.build(), HttpResponse.BodyHandlers.ofInputStream());
      try (InputStream body = response.body()) {
        body.readNBytes(1024);
      }
      if (remote.ecosystem().isRegistry() && response.statusCode() == 401) {
        return new Result(true, "Reachable: the registry answered HTTP 401, its normal reply "
            + "before a client asks for a token.");
      }
      return verdict(response.statusCode(), uri);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return new Result(false, "The check was interrupted.");
    } catch (IOException exception) {
      return new Result(false, describe(exception, uri));
    } catch (GeneralSecurityException exception) {
      return new Result(false, "The CA bundle could not be used: " + exception.getMessage());
    }
  }

  static String probe(PackageToolConfig.Remote remote) {
    String base = remote.url().endsWith("/")
        ? remote.url().substring(0, remote.url().length() - 1) : remote.url();
    return switch (remote.ecosystem()) {
      case APK -> base + "/x86_64/APKINDEX.tar.gz";
      case GO -> base + "/github.com/google/uuid/@v/list";
      case NPM -> base + "/-/ping";
      case MAVEN, GRADLE, PYPI -> base + "/";
      case CARGO -> base.replaceFirst("^(sparse|registry)\\+", "") + "/config.json";
      case COMPOSER -> base + "/packages.json";
      case RUBYGEMS -> base + "/specs.4.8.gz";
      case NUGET -> remote.url();
      case DOCKER_HUB, GHCR -> base + "/v2/";
    };
  }

  private static Result verdict(int status, URI uri) {
    String where = uri.getHost();
    if (status >= 200 && status < 300) return new Result(true, "Reachable (HTTP " + status + ").");
    if (status == 401 || status == 403) {
      return new Result(false, where + " answered HTTP " + status
          + ": the credentials are missing, wrong or not allowed.");
    }
    if (status == 404) {
      return new Result(false, where + " is reachable but the path was not found (HTTP 404). "
          + "Check that the URL is the repository's base URL.");
    }
    if (status >= 300 && status < 400) {
      return new Result(false, where + " redirected (HTTP " + status + "); use the final URL.");
    }
    return new Result(false, where + " answered HTTP " + status + ".");
  }

  private static SSLContext context(PackageToolConfig.Trust trust, boolean skipVerification)
      throws GeneralSecurityException, IOException {
    SSLContext context = SSLContext.getInstance("TLS");
    if (skipVerification) {
      context.init(null, new TrustManager[] {new TrustAll()}, null);
    } else if (trust.truststore() != null) {
      KeyStore store = KeyStore.getInstance("PKCS12");
      try (InputStream in = Files.newInputStream(trust.truststore())) {
        store.load(in, trust.truststorePassword().toCharArray());
      }
      TrustManagerFactory factory = TrustManagerFactory.getInstance(
          TrustManagerFactory.getDefaultAlgorithm());
      factory.init(store);
      context.init(null, factory.getTrustManagers(), null);
    } else {
      context.init(null, null, null);
    }
    return context;
  }

  private static String describe(IOException exception, URI uri) {
    Throwable cause = exception;
    while (cause != null) {
      if (cause instanceof java.net.UnknownHostException) {
        return "The host name " + uri.getHost() + " cannot be resolved from the Petri server.";
      }
      if (cause instanceof java.net.http.HttpConnectTimeoutException
          || cause instanceof java.net.http.HttpTimeoutException) {
        return "No answer from " + uri.getHost() + " in time (network or firewall?).";
      }
      if (cause instanceof java.net.ConnectException) {
        return "The connection to " + uri.getHost() + " was refused or is unreachable.";
      }
      if (cause instanceof javax.net.ssl.SSLException) {
        return "The TLS connection to " + uri.getHost() + " failed (" + cause.getMessage()
            + "). Add the CA certificate under TLS, or do not verify this remote.";
      }
      cause = cause.getCause();
    }
    return "The request failed: " + exception.getClass().getSimpleName();
  }

  /** Accepts every certificate and host name; only used when the administrator chose that. */
  private static final class TrustAll extends X509ExtendedTrustManager {
    @Override public void checkClientTrusted(X509Certificate[] chain, String type) {}
    @Override public void checkServerTrusted(X509Certificate[] chain, String type) {}
    @Override public void checkClientTrusted(X509Certificate[] chain, String type, java.net.Socket socket) {}
    @Override public void checkServerTrusted(X509Certificate[] chain, String type, java.net.Socket socket) {}
    @Override public void checkClientTrusted(X509Certificate[] chain, String type, SSLEngine engine) {}
    @Override public void checkServerTrusted(X509Certificate[] chain, String type, SSLEngine engine) {}
    @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
  }
}
