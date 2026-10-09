package tech.wenisch.petri.agent.model;

public interface ModelClient {
  ModelProvider provider();
  ModelResponse respond(ModelRequest request);

  default ModelResponse respondStreaming(ModelRequest request, ModelStreamListener listener) {
    ModelResponse response = respond(request);
    if (response.text() != null && !response.text().isEmpty()) listener.onTextDelta(response.text());
    return response;
  }
}
