package tech.wenisch.petri.agent.model;

public interface ModelClient {
  ModelProvider provider();
  ModelResponse respond(ModelRequest request);
}
