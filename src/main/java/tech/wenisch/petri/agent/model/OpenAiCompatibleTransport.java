package tech.wenisch.petri.agent.model;

import java.io.IOException;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tech.wenisch.petri.config.PetriProperties;
import tech.wenisch.petri.config.TlsSettings;

@Component
final class OpenAiCompatibleTransport {
  private final ModelCredentialService credentials;
  private final HttpClient httpClient;
  private final Duration timeout;
  private final int responseLimit;

  @Autowired
  OpenAiCompatibleTransport(
      ModelCredentialService credentials, PetriProperties properties, TlsSettings tls) {
    this(
        credentials,
        tls.apply(HttpClient.newBuilder()
            .connectTimeout(properties.agent().modelRequestTimeout())
            .followRedirects(HttpClient.Redirect.NEVER))
            .build(),
        properties.agent().modelRequestTimeout(),
        properties.agent().maxModelResponseBytes());
  }

  OpenAiCompatibleTransport(
      ModelCredentialService credentials,
      HttpClient httpClient,
      Duration timeout,
      int responseLimit) {
    this.credentials = credentials;
    this.httpClient = httpClient;
    this.timeout = timeout;
    this.responseLimit = responseLimit;
  }

  byte[] post(ModelProvider provider, String resource, String json) {
    return post(provider, null, resource, json);
  }

  /** A null reference addresses the oldest global instance of the kind with its shared key. */
  byte[] post(ModelProvider provider, ModelCredentialRef ref, String resource, String json) {
    ModelCredentialMaterial credential = material(provider, ref);
    URI uri = endpoint(credential.baseUrl(), resource);
    HttpRequest.Builder request = HttpRequest.newBuilder(uri)
        .timeout(timeout)
        .header("Content-Type", "application/json")
        .header("Accept", "application/json");
    if (credential.apiKey() != null && !credential.apiKey().isBlank()) {
      request.header("Authorization", bearer(credential.apiKey()));
    }
    try {
      HttpResponse<InputStream> response = httpClient.send(
          request.POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build(),
          HttpResponse.BodyHandlers.ofInputStream());
      byte[] body;
      try (InputStream input = response.body()) {
        body = input.readNBytes(responseLimit + 1);
      }
      if (body.length > responseLimit) {
        throw new ModelClientException(
            provider.displayName() + " response exceeded the configured byte limit");
      }
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        String requestId = response.headers().firstValue("x-request-id").orElse("unavailable");
        throw new ModelClientException(
            provider.displayName() + " request failed with HTTP " + response.statusCode()
                + " (request " + requestId + ")");
      }
      return body;
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new ModelClientException(provider.displayName() + " request was interrupted", exception);
    } catch (IOException exception) {
      throw new ModelClientException(provider.displayName() + " request failed: " + describe(exception, uri), exception);
    }
  }

  /** Lists what the instance's endpoint offers, using the viewer's key before the shared one. */
  byte[] get(ModelCredentialRef ref, String resource) {
    ModelCredentialMaterial credential = credentials.requireMaterial(ref);
    URI uri = endpoint(credential.baseUrl(), resource);
    HttpRequest.Builder request = HttpRequest.newBuilder(uri)
        .timeout(timeout)
        .header("Accept", "application/json");
    if (credential.apiKey() != null && !credential.apiKey().isBlank()) {
      request.header("Authorization", bearer(credential.apiKey()));
    }
    String name = credential.provider().displayName();
    try {
      HttpResponse<InputStream> response =
          httpClient.send(request.GET().build(), HttpResponse.BodyHandlers.ofInputStream());
      byte[] body;
      try (InputStream input = response.body()) {
        body = input.readNBytes(responseLimit + 1);
      }
      if (body.length > responseLimit) {
        throw new ModelClientException(name + " response exceeded the configured byte limit");
      }
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        throw new ModelClientException(
            name + " request failed with HTTP " + response.statusCode());
      }
      return body;
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new ModelClientException(name + " request was interrupted", exception);
    } catch (IOException exception) {
      throw new ModelClientException(name + " request failed: " + describe(exception, uri), exception);
    }
  }

  void postSse(ModelProvider provider, String resource, String json, Consumer<String> eventData) {
    postSse(provider, null, resource, json, eventData);
  }

  void postSse(
      ModelProvider provider, ModelCredentialRef ref, String resource, String json,
      Consumer<String> eventData) {
    ModelCredentialMaterial credential = material(provider, ref);
    URI uri = endpoint(credential.baseUrl(), resource);
    HttpRequest.Builder request = HttpRequest.newBuilder(uri)
        .timeout(timeout)
        .header("Content-Type", "application/json")
        .header("Accept", "text/event-stream");
    if (credential.apiKey() != null && !credential.apiKey().isBlank()) {
      request.header("Authorization", bearer(credential.apiKey()));
    }
    try {
      HttpResponse<InputStream> response = httpClient.send(
          request.POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build(),
          HttpResponse.BodyHandlers.ofInputStream());
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        byte[] error;
        try (InputStream input = response.body()) { error = input.readNBytes(responseLimit); }
        String message = new String(error, StandardCharsets.UTF_8).toLowerCase();
        if ((response.statusCode() == 400 || response.statusCode() == 404
            || response.statusCode() == 405 || response.statusCode() == 415
            || response.statusCode() == 501) && message.contains("stream")) {
          throw new ModelStreamingNotSupportedException(
              provider.displayName() + " endpoint does not support streaming");
        }
        throw new ModelClientException(provider.displayName() + " streaming request failed with HTTP "
            + response.statusCode());
      }
      String contentType = response.headers().firstValue("content-type").orElse("").toLowerCase();
      if (!contentType.contains("text/event-stream")) {
        try (InputStream input = response.body()) { input.readNBytes(responseLimit); }
        throw new ModelStreamingNotSupportedException(
            provider.displayName() + " endpoint returned a non-streaming response");
      }
      try (BufferedReader reader = new BufferedReader(new InputStreamReader(
          response.body(), StandardCharsets.UTF_8))) {
        String line;
        StringBuilder data = new StringBuilder();
        while ((line = reader.readLine()) != null) {
          if (line.startsWith("data:")) {
            if (!data.isEmpty()) data.append('\n');
            data.append(line.substring(5).stripLeading());
          } else if (line.isEmpty() && !data.isEmpty()) {
            eventData.accept(data.toString());
            data.setLength(0);
          }
        }
        if (!data.isEmpty()) eventData.accept(data.toString());
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new ModelClientException(provider.displayName() + " streaming request was interrupted", exception);
    } catch (IOException exception) {
      throw new ModelClientException(provider.displayName() + " streaming request failed: " + describe(exception, uri), exception);
    }
  }

  private ModelCredentialMaterial material(ModelProvider provider, ModelCredentialRef ref) {
    return ref == null || ref.instanceId() == null
        ? credentials.requireMaterial(provider)
        : credentials.requireMaterial(ref);
  }

  /**
   * The Authorization header value. A key pasted with a line break or a space inside cannot be sent
   * (the JDK rejects the header), so say so instead of failing as an unspecific invalid response.
   */
  private static String bearer(String apiKey) {
    String key = apiKey.strip();
    for (int index = 0; index < key.length(); index++) {
      char value = key.charAt(index);
      if (value < 33 || value > 126) {
        throw new ModelClientException("The API key of this provider contains a space, line break "
            + "or non-ASCII character and cannot be sent. Enter the key again without it.");
      }
    }
    return "Bearer " + key;
  }

  private static URI endpoint(String baseUrl, String resource) {
    try {
      return URI.create(baseUrl + resource);
    } catch (IllegalArgumentException exception) {
      throw new ModelClientException(
          "The base URL of this provider is not a valid URL. Check it in the provider settings.");
    }
  }

  /**
   * A short, secret-free reason for a transport failure, so "request failed" tells the user whether
   * the host cannot be found, refuses the connection, times out or breaks TLS.
   */
  static String describe(IOException exception, URI uri) {
    String target = uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
    Throwable cause = exception;
    while (cause != null) {
      if (cause instanceof java.net.UnknownHostException) {
        return "the host name " + uri.getHost() + " cannot be resolved from the Petri server";
      }
      if (cause instanceof java.net.http.HttpConnectTimeoutException) {
        return "could not connect to " + target + " in time (network or firewall?)";
      }
      if (cause instanceof java.net.http.HttpTimeoutException) {
        return "the provider at " + target + " did not answer in time";
      }
      if (cause instanceof java.net.ConnectException) {
        return "connection to " + target + " was refused or unreachable from the Petri server";
      }
      if (cause instanceof javax.net.ssl.SSLException) {
        return "the TLS connection to " + target + " failed (" + cause.getClass().getSimpleName()
            + "); check the certificate or the server's trust store";
      }
      cause = cause.getCause();
    }
    String message = exception.getMessage();
    String detail = message == null || message.isBlank() ? "" : ": " + (message.length() > 160
        ? message.substring(0, 160) : message);
    return exception.getClass().getSimpleName() + detail + " (" + target + ")";
  }
}
