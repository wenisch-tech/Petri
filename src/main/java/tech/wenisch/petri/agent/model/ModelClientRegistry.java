package tech.wenisch.petri.agent.model;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class ModelClientRegistry {
  private final Map<ModelProvider, ModelClient> clients;

  public ModelClientRegistry(List<ModelClient> clients) {
    Map<ModelProvider, ModelClient> registered = new EnumMap<>(ModelProvider.class);
    for (ModelClient client : clients) {
      ModelClient previous = registered.put(client.provider(), client);
      if (previous != null) {
        throw new IllegalStateException("Duplicate model client for " + client.provider());
      }
    }
    this.clients = Map.copyOf(registered);
  }

  public ModelClient require(ModelProvider provider) {
    ModelClient client = clients.get(provider);
    if (client == null) throw new IllegalArgumentException("Unsupported model provider: " + provider);
    return client;
  }
}
