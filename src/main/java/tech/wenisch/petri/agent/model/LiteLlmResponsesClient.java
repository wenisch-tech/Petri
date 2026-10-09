package tech.wenisch.petri.agent.model;

import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class LiteLlmResponsesClient implements ModelClient {
  private final OpenAiCompatibleTransport transport;
  private final OpenAiResponsesCodec codec;

  LiteLlmResponsesClient(OpenAiCompatibleTransport transport, ObjectMapper objectMapper) {
    this.transport = transport;
    this.codec = new OpenAiResponsesCodec(objectMapper);
  }

  @Override
  public ModelProvider provider() {
    return ModelProvider.LITELLM;
  }

  @Override
  public ModelResponse respond(ModelRequest request) {
    return codec.decode(transport.post(provider(), "/responses", codec.encode(request)));
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
