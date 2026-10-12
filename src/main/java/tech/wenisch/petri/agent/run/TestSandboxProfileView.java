package tech.wenisch.petri.agent.run;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public record TestSandboxProfileView(
    UUID id,
    String name,
    String image,
    Set<TestBuildSystem> buildSystems,
    String cpuLimit,
    String memoryLimit,
    int pidsLimit,
    Duration timeout,
    int outputLimitBytes,
    boolean networkEnabled,
    boolean enabled,
    boolean defaultProfile,
    Map<String, String> environment) {
  public TestSandboxProfileView {
    environment = environment == null ? Map.of() : Map.copyOf(environment);
  }
}
