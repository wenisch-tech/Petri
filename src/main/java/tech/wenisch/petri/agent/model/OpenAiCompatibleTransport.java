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

  void postSse(ModelProvider provider, String resource, String json, Consumer<String> eventData) {
    ModelCredentialMaterial credential = credentials.requireMaterial(provider);
    URI uri = URI.create(credential.baseUrl() + resource);
    HttpRequest.Builder request = HttpRequest.newBuilder(uri)
        .timeout(timeout)
        .header("Content-Type", "application/json")
        .header("Accept", "text/event-stream");
    if (credential.apiKey() != null && !credential.apiKey().isBlank()) {
      request.header("Authorization", "Bearer " + credential.apiKey());
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
      throw new ModelClientException(provider.displayName() + " streaming request failed", exception);
    }
  }
}
