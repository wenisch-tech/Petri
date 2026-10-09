package tech.wenisch.petri.agent.model;

import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class OllamaChatCompletionsClient implements ModelClient {
  private final OpenAiCompatibleTransport transport;
  private final OpenAiChatCompletionsCodec codec;

  OllamaChatCompletionsClient(OpenAiCompatibleTransport transport, ObjectMapper objectMapper) {
    this.transport = transport;
    this.codec = new OpenAiChatCompletionsCodec(objectMapper, true);
  }

  @Override
  public ModelProvider provider() {
    return ModelProvider.OLLAMA;
  }

  @Override
  public ModelResponse respond(ModelRequest request) {
    return codec.decode(transport.post(provider(), "/chat/completions", codec.encode(request)));
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
