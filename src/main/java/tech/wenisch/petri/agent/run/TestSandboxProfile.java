package tech.wenisch.petri.agent.run;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "test_sandbox_profile")
public class TestSandboxProfile {
  @Id private UUID id;
  @Column(nullable = false, unique = true, length = 120) private String name;
  @Column(nullable = false, length = 1000) private String image;
  @Column(name = "allowed_build_systems", nullable = false, length = 255) private String allowedBuildSystems;
  @Column(name = "cpu_limit", nullable = false, length = 32) private String cpuLimit;
  @Column(name = "memory_limit", nullable = false, length = 32) private String memoryLimit;
  @Column(name = "pids_limit", nullable = false) private int pidsLimit;
  @Column(name = "timeout_seconds", nullable = false) private int timeoutSeconds;
  @Column(name = "output_limit_bytes", nullable = false) private int outputLimitBytes;
  @Column(name = "network_enabled", nullable = false) private boolean networkEnabled;
  @Column(nullable = false) private boolean enabled;
  @Column(name = "default_profile", nullable = false) private boolean defaultProfile;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;

  protected TestSandboxProfile() {}

  public TestSandboxProfile(UUID id, String name, String image, Set<TestBuildSystem> buildSystems,
      String cpuLimit, String memoryLimit, int pidsLimit, int timeoutSeconds,
      int outputLimitBytes, boolean networkEnabled, boolean enabled, Instant now) {
    this.id = id;
    this.createdAt = now;
    update(name, image, buildSystems, cpuLimit, memoryLimit, pidsLimit, timeoutSeconds,
        outputLimitBytes, networkEnabled, enabled, now);
  }

  public void update(String name, String image, Set<TestBuildSystem> buildSystems,
      String cpuLimit, String memoryLimit, int pidsLimit, int timeoutSeconds,
      int outputLimitBytes, boolean networkEnabled, boolean enabled, Instant now) {
    this.name = name;
    this.image = image;
    this.allowedBuildSystems = buildSystems.stream().map(Enum::name).sorted()
        .collect(java.util.stream.Collectors.joining(","));
    this.cpuLimit = cpuLimit;
    this.memoryLimit = memoryLimit;
    this.pidsLimit = pidsLimit;
    this.timeoutSeconds = timeoutSeconds;
    this.outputLimitBytes = outputLimitBytes;
    this.networkEnabled = networkEnabled;
    this.enabled = enabled;
    this.updatedAt = now;
  }

  public Set<TestBuildSystem> buildSystems() {
    EnumSet<TestBuildSystem> result = EnumSet.noneOf(TestBuildSystem.class);
    for (String value : allowedBuildSystems.split(",")) if (!value.isBlank()) result.add(TestBuildSystem.valueOf(value));
    return result;
  }

  public UUID getId() { return id; }
  public String getName() { return name; }
  public String getImage() { return image; }
  public String getAllowedBuildSystems() { return allowedBuildSystems; }
  public String getCpuLimit() { return cpuLimit; }
  public String getMemoryLimit() { return memoryLimit; }
  public int getPidsLimit() { return pidsLimit; }
  public int getTimeoutSeconds() { return timeoutSeconds; }
  public int getOutputLimitBytes() { return outputLimitBytes; }
  public boolean isNetworkEnabled() { return networkEnabled; }
  public boolean isEnabled() { return enabled; }
  public boolean isDefaultProfile() { return defaultProfile; }
  public Instant getCreatedAt() { return createdAt; }
  public Instant getUpdatedAt() { return updatedAt; }
}
