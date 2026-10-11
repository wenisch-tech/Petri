package tech.wenisch.petri.git.provider;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class GitProviderAdapterRegistry {
  private final Map<GitProviderType, GitProviderAdapter> adapters;

  public GitProviderAdapterRegistry(List<GitProviderAdapter> adapters) {
    this.adapters = new EnumMap<>(GitProviderType.class);
    adapters.forEach(adapter -> this.adapters.put(adapter.type(), adapter));
  }

  public GitProviderAdapter require(GitProviderType type) {
    GitProviderAdapter adapter = adapters.get(type);
    if (adapter == null) throw new IllegalArgumentException("Unsupported Git provider " + type);
    return adapter;
  }
}
