package tech.wenisch.petri.agent.model;

import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class GenericOpenAiChatCompletionsClient implements ModelClient {
  private final OpenAiCompatibleTransport transport;
  private final OpenAiChatCompletionsCodec codec;

  GenericOpenAiChatCompletionsClient(
      OpenAiCompatibleTransport transport, ObjectMapper objectMapper) {
    this.transport = transport;
    this.codec = new OpenAiChatCompletionsCodec(objectMapper, false);
  }

  @Override
  public ModelProvider provider() {
    return ModelProvider.OPENAI_COMPATIBLE;
  }

  @Override
  public ModelResponse respond(ModelRequest request) {
    return codec.decode(transport.post(provider(), "/chat/completions", codec.encode(request)));
  }
}
