package tech.wenisch.petri.agent.model;

import tools.jackson.databind.ObjectMapper;
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
public class OpenAiResponsesClient implements ModelClient {
  private final ModelCredentialService credentials;
  private final HttpClient httpClient;
  private final OpenAiResponsesCodec codec;
  private final URI responsesUri;
  private final Duration timeout;
  private final int responseLimit;

  @Autowired
  public OpenAiResponsesClient(
      ModelCredentialService credentials, ObjectMapper objectMapper, PetriProperties properties) {
    this(
        credentials,
        HttpClient.newBuilder()
            .connectTimeout(properties.agent().modelRequestTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build(),
        new OpenAiResponsesCodec(objectMapper),
        properties);
  }

  OpenAiResponsesClient(
      ModelCredentialService credentials,
      HttpClient httpClient,
      OpenAiResponsesCodec codec,
      PetriProperties properties) {
    this.credentials = credentials;
    this.httpClient = httpClient;
    this.codec = codec;
    this.responsesUri = URI.create(stripTrailingSlash(properties.agent().openAiBaseUrl()) + "/responses");
    this.timeout = properties.agent().modelRequestTimeout();
    this.responseLimit = properties.agent().maxModelResponseBytes();
  }

  @Override
  public ModelProvider provider() {
    return ModelProvider.OPENAI;
  }

  @Override
  public ModelResponse respond(ModelRequest request) {
    ModelCredentialMaterial credential = credentials.requireMaterial(ModelProvider.OPENAI);
    if (!credential.model().equals(request.model())) {
      throw new IllegalArgumentException("Requested model does not match configured model");
    }
    HttpRequest httpRequest = HttpRequest.newBuilder(responsesUri)
        .timeout(timeout)
        .header("Authorization", "Bearer " + credential.apiKey())
        .header("Content-Type", "application/json")
        .header("Accept", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(codec.encode(request), StandardCharsets.UTF_8))
        .build();
    try {
      HttpResponse<InputStream> response =
          httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream());
      byte[] body;
      try (InputStream input = response.body()) {
        body = input.readNBytes(responseLimit + 1);
      }
      if (body.length > responseLimit) {
        throw new ModelClientException("OpenAI response exceeded the configured byte limit");
      }
      if (response.statusCode() < 200 || response.statusCode() >= 300) {
        String requestId = response.headers().firstValue("x-request-id").orElse("unavailable");
        throw new ModelClientException(
            "OpenAI request failed with HTTP " + response.statusCode() + " (request " + requestId + ")");
      }
      return codec.decode(body);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new ModelClientException("OpenAI request was interrupted", exception);
    } catch (IOException exception) {
      throw new ModelClientException("OpenAI request failed", exception);
    }
  }

  private static String stripTrailingSlash(String value) {
    int end = value.length();
    while (end > 0 && value.charAt(end - 1) == '/') end--;
    return value.substring(0, end);
  }
}
