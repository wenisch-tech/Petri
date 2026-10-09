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
}
