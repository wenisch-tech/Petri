package tech.wenisch.petri.agent.model;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tech.wenisch.petri.config.PetriProperties;

@Component
final class OpenAiCompatibleTransport {
  private final ModelCredentialService credentials;
  private final HttpClient httpClient;
  private final Duration timeout;
  private final int responseLimit;

  @Autowired
  OpenAiCompatibleTransport(
      ModelCredentialService credentials, PetriProperties properties) {
    this(
        credentials,
        HttpClient.newBuilder()
            .connectTimeout(properties.agent().modelRequestTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
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
    ModelCredentialMaterial credential = credentials.requireMaterial(provider);
    URI uri = URI.create(credential.baseUrl() + resource);
    HttpRequest.Builder request = HttpRequest.newBuilder(uri)
        .timeout(timeout)
        .header("Content-Type", "application/json")
        .header("Accept", "application/json");
    if (credential.apiKey() != null && !credential.apiKey().isBlank()) {
      request.header("Authorization", "Bearer " + credential.apiKey());
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
      throw new ModelClientException(provider.displayName() + " request failed", exception);
    }
  }
}
