package tech.wenisch.petri.agent.run;

import java.time.Duration;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.audit.AuditLogService;

@Service
public class TestSandboxProfileService {
  private final TestSandboxProfileRepository profiles;
  private final ProjectSandboxSettingRepository settings;
  private final AuditLogService audit;

  public TestSandboxProfileService(TestSandboxProfileRepository profiles,
      ProjectSandboxSettingRepository settings, AuditLogService audit) {
    this.profiles = profiles;
    this.settings = settings;
    this.audit = audit;
  }

  @Transactional(readOnly = true)
  public List<TestSandboxProfile> all() { return profiles.findAllByOrderByNameAsc(); }

  @Transactional(readOnly = true)
  public TestSandboxProfileView resolve(UUID projectId, TestBuildSystem buildSystem) {
    ProjectSandboxSetting setting = settings.findById(projectId).orElse(null);
    TestSandboxProfile profile = setting == null
        ? profiles.findFirstByDefaultProfileTrueAndEnabledTrue()
            .orElseThrow(() -> new IllegalStateException("No default test sandbox profile is available"))
        : profiles.findById(setting.profileId())
            .filter(TestSandboxProfile::isEnabled)
            .orElseThrow(() -> new IllegalStateException("Configured test sandbox profile is unavailable"));
    if (!profile.buildSystems().contains(buildSystem)) {
      throw new IllegalStateException("Sandbox profile does not allow " + buildSystem);
    }
    boolean network = setting != null && setting.networkEnabled() != null
        ? setting.networkEnabled() : profile.isNetworkEnabled();
    return view(profile, network);
  }

  @Transactional
  public TestSandboxProfile save(UUID id, String name, String image,
      Set<TestBuildSystem> buildSystems, String cpus, String memory, int pids,
      int timeoutSeconds, int outputBytes, boolean network, boolean enabled, String actorEmail) {
    name = required(name, "Name", 120); image = validImage(image);
    cpus = validCpu(cpus); memory = validMemory(memory);
    if (buildSystems == null || buildSystems.isEmpty()) throw new IllegalArgumentException("At least one build system is required");
    if (pids < 16 || timeoutSeconds < 1 || outputBytes < 1024) throw new IllegalArgumentException("Sandbox limits are invalid");
    Instant now = Instant.now();
    TestSandboxProfile profile = id == null
        ? new TestSandboxProfile(UUID.randomUUID(), name, image, EnumSet.copyOf(buildSystems), cpus,
            memory, pids, timeoutSeconds, outputBytes, network, enabled, now)
        : profiles.findById(id).orElseThrow(() -> new IllegalArgumentException("Sandbox profile not found"));
    if (id != null) profile.update(name, image, EnumSet.copyOf(buildSystems), cpus, memory,
        pids, timeoutSeconds, outputBytes, network, enabled, now);
    profiles.save(profile);
    audit.record(actorEmail, "TEST_SANDBOX_PROFILE_SAVED", "TEST_SANDBOX_PROFILE", profile.getId(),
        "Saved test sandbox profile " + profile.getName());
    return profile;
  }

  /** Project-wide approval switches shown in the workspace (default off). */
  @Transactional(readOnly = true)
  public ProjectApprovalSettings approvalSettings(UUID projectId) {
    return settings.findById(projectId)
        .map(value -> new ProjectApprovalSettings(
            value.autoApprovePackageInstalls(), value.autoApproveTerminalCommands()))
        .orElse(new ProjectApprovalSettings(false, false));
  }

  /** Saves the approval switches, creating the project's setting row on the default profile. */
  @Transactional
  public ProjectApprovalSettings saveApprovalSettings(
      UUID projectId, boolean autoApprovePackageInstalls, boolean autoApproveTerminalCommands,
      String actorEmail) {
    ProjectSandboxSetting setting = settings.findById(projectId).orElseGet(() -> {
      UUID profileId = profiles.findFirstByDefaultProfileTrueAndEnabledTrue()
          .orElseThrow(() -> new IllegalStateException("No default test sandbox profile is available"))
          .getId();
      return new ProjectSandboxSetting(projectId, profileId, null);
    });
    setting.autoApprovePackageInstalls(autoApprovePackageInstalls);
    setting.autoApproveTerminalCommands(autoApproveTerminalCommands);
    settings.save(setting);
    audit.record(actorEmail, "PROJECT_APPROVAL_SETTINGS_SAVED", "PROJECT", projectId,
        "Package installs auto-approve=" + autoApprovePackageInstalls
            + ", terminal commands auto-approve=" + autoApproveTerminalCommands);
    return new ProjectApprovalSettings(autoApprovePackageInstalls, autoApproveTerminalCommands);
  }

  @Transactional
  public void configureProject(
      UUID projectId, UUID profileId, Boolean networkEnabled, boolean autoApprovePackageInstalls,
      boolean autoApproveTerminalCommands, String actorEmail) {
    if (!profiles.existsById(profileId)) throw new IllegalArgumentException("Sandbox profile not found");
    ProjectSandboxSetting setting = settings.findById(projectId)
        .orElseGet(() -> new ProjectSandboxSetting(projectId, profileId, networkEnabled));
    setting.update(profileId, networkEnabled);
    setting.autoApprovePackageInstalls(autoApprovePackageInstalls);
    setting.autoApproveTerminalCommands(autoApproveTerminalCommands);
    settings.save(setting);
    audit.record(actorEmail, "PROJECT_TEST_SANDBOX_CONFIGURED", "PROJECT", projectId,
        "Configured isolated test sandbox for project");
  }

  static TestSandboxProfileView view(TestSandboxProfile profile, boolean network) {
    return new TestSandboxProfileView(profile.getId(), profile.getName(), profile.getImage(),
        Set.copyOf(profile.buildSystems()), profile.getCpuLimit(), profile.getMemoryLimit(),
        profile.getPidsLimit(), Duration.ofSeconds(profile.getTimeoutSeconds()),
        profile.getOutputLimitBytes(), network, profile.isEnabled(), profile.isDefaultProfile());
  }

  private static String required(String value, String label, int max) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required");
    value = value.trim(); if (value.length() > max) throw new IllegalArgumentException(label + " is too long");
    return value;
  }

  private static String validImage(String value) {
    value = required(value, "Image", 1000);
    if (value.startsWith("-") || value.chars().anyMatch(Character::isWhitespace)
        || value.chars().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException("Container image reference is invalid");
    }
    return value;
  }

  private static String validCpu(String value) {
    value = required(value, "CPU limit", 32);
    try {
      if (new BigDecimal(value).compareTo(BigDecimal.ZERO) <= 0) {
        throw new IllegalArgumentException("CPU limit must be greater than zero");
      }
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException("CPU limit must be a positive number", exception);
    }
    return value;
  }

  private static String validMemory(String value) {
    value = required(value, "Memory limit", 32).toLowerCase(java.util.Locale.ROOT);
    if (!value.matches("[1-9][0-9]*(?:[kmgtp](?:i?b)?)?")) {
      throw new IllegalArgumentException("Memory limit must be bytes or a value such as 512m or 2g");
    }
    return value;
  }
}
