package tech.wenisch.petri.agent.model;

import tools.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tech.wenisch.petri.config.PetriProperties;

@Component
public class OpenAiResponsesClient implements ModelClient {
  private final OpenAiCompatibleTransport transport;
  private final OpenAiResponsesCodec codec;

  @Autowired
  public OpenAiResponsesClient(
      OpenAiCompatibleTransport transport, ObjectMapper objectMapper) {
    this(transport, new OpenAiResponsesCodec(objectMapper));
  }

  OpenAiResponsesClient(
      ModelCredentialService credentials,
      HttpClient httpClient,
      OpenAiResponsesCodec codec,
      PetriProperties properties) {
    this(
        new OpenAiCompatibleTransport(
            credentials,
            httpClient,
            properties.agent().modelRequestTimeout(),
            properties.agent().maxModelResponseBytes()),
        codec);
  }

  private OpenAiResponsesClient(
      OpenAiCompatibleTransport transport, OpenAiResponsesCodec codec) {
    this.transport = transport;
    this.codec = codec;
  }

  @Override
  public ModelProvider provider() {
    return ModelProvider.OPENAI;
  }

  @Override
  public ModelResponse respond(ModelRequest request) {
    return codec.decode(transport.post(
        provider(), request.credential(), "/responses", codec.encode(request)));
  }

  @Override
  public ModelResponse respondStreaming(ModelRequest request, ModelStreamListener listener) {
    boolean[] emitted = {false};
    ModelStreamListener tracking = ModelStreamListener.tracking(listener, () -> emitted[0] = true);
    try {
      return codec.stream(transport, provider(), request, tracking);
    } catch (ModelStreamingNotSupportedException exception) {
      if (emitted[0]) throw exception;
      return ModelClient.super.respondStreaming(request, listener);
    }
  }
}
