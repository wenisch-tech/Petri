package tech.wenisch.petri.agent.run;

import java.time.Duration;
import java.time.Instant;
import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.audit.AuditLogService;
import tech.wenisch.petri.secret.SecretCipher;

@Service
public class TestSandboxProfileService {
  private static final String MASK = "********";

  private final TestSandboxProfileRepository profiles;
  private final ProjectSandboxSettingRepository settings;
  private final AuditLogService audit;
  private final SecretCipher cipher;

  public TestSandboxProfileService(TestSandboxProfileRepository profiles,
      ProjectSandboxSettingRepository settings, AuditLogService audit, SecretCipher cipher) {
    this.profiles = profiles;
    this.settings = settings;
    this.audit = audit;
    this.cipher = cipher;
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
    return view(profile, network, mergedEnvironment(profile, setting));
  }

  /**
   * Profile variables for a project's commands (terminal, package installs). Lenient: no build
   * system check, and empty when no profile can be resolved.
   */
  @Transactional(readOnly = true)
  public Map<String, String> environmentFor(UUID projectId) {
    ProjectSandboxSetting setting = settings.findById(projectId).orElse(null);
    TestSandboxProfile profile = setting == null
        ? profiles.findFirstByDefaultProfileTrueAndEnabledTrue().orElse(null)
        : profiles.findById(setting.profileId()).filter(TestSandboxProfile::isEnabled).orElse(null);
    return profile == null ? Map.of() : mergedEnvironment(profile, setting);
  }

  /** The profile's variables with the project's own on top: the project wins on a clash. */
  private Map<String, String> mergedEnvironment(TestSandboxProfile profile, ProjectSandboxSetting setting) {
    Map<String, String> merged = new java.util.LinkedHashMap<>(environment(profile));
    merged.putAll(projectVariables(setting));
    return merged;
  }

  private Map<String, String> projectVariables(ProjectSandboxSetting setting) {
    String stored = setting == null ? null : setting.environmentCiphertext();
    if (stored == null || stored.isBlank()) return Map.of();
    return CommandEnvironment.parse(cipher.decrypt(stored));
  }

  /** The project's own variables as editable text; values are masked so they never reach the page. */
  @Transactional(readOnly = true)
  public String maskedProjectEnvironment(UUID projectId) {
    StringBuilder text = new StringBuilder();
    projectVariables(settings.findById(projectId).orElse(null))
        .keySet().forEach(name -> text.append(name).append('=').append(MASK).append('\n'));
    return text.toString();
  }

  /** Names (never values) the project inherits from its sandbox profile. */
  @Transactional(readOnly = true)
  public List<String> inheritedVariableNames(UUID projectId) {
    ProjectSandboxSetting setting = settings.findById(projectId).orElse(null);
    TestSandboxProfile profile = setting == null
        ? profiles.findFirstByDefaultProfileTrueAndEnabledTrue().orElse(null)
        : profiles.findById(setting.profileId()).orElse(null);
    return profile == null ? List.of() : environment(profile).keySet().stream().sorted().toList();
  }

  /** Replaces the project's variables. An unchanged masked value keeps the stored one. */
  @Transactional
  public void saveProjectEnvironment(UUID projectId, String text, String actorEmail) {
    Map<String, String> variables = CommandEnvironment.parse(text);
    ProjectSandboxSetting setting = settings.findById(projectId).orElseGet(() -> {
      UUID profileId = profiles.findFirstByDefaultProfileTrueAndEnabledTrue()
          .orElseThrow(() -> new IllegalStateException("No default test sandbox profile is available"))
          .getId();
      return new ProjectSandboxSetting(projectId, profileId, null);
    });
    Map<String, String> current = projectVariables(setting);
    variables.replaceAll((name, value) ->
        MASK.equals(value) && current.containsKey(name) ? current.get(name) : value);
    if (variables.containsValue(MASK)) {
      throw new IllegalArgumentException("Enter a value for each new environment variable");
    }
    setting.environmentCiphertext(variables.isEmpty() ? null
        : cipher.encrypt(CommandEnvironment.format(variables)));
    settings.save(setting);
    // Only names are audited; values may be credentials.
    audit.record(actorEmail, "PROJECT_ENVIRONMENT_SAVED", "PROJECT", projectId,
        "Saved project environment variables " + variables.keySet());
  }

  /** The profile's variables as editable text; values are masked so they never reach the page. */
  public String maskedEnvironment(TestSandboxProfile profile) {
    StringBuilder text = new StringBuilder();
    environment(profile).keySet().forEach(name -> text.append(name).append('=').append(MASK).append('\n'));
    return text.toString();
  }

  Map<String, String> environment(TestSandboxProfile profile) {
    String stored = profile.getEnvironmentCiphertext();
    if (stored == null || stored.isBlank()) return Map.of();
    return CommandEnvironment.parse(cipher.decrypt(stored));
  }

  @Transactional
  public TestSandboxProfile save(UUID id, String name, String image,
      Set<TestBuildSystem> buildSystems, String cpus, String memory, int pids,
      int timeoutSeconds, int outputBytes, boolean network, boolean enabled, String environmentText,
      String actorEmail) {
    name = required(name, "Name", 120); image = validImage(image);
    cpus = validCpu(cpus); memory = validMemory(memory);
    if (buildSystems == null || buildSystems.isEmpty()) throw new IllegalArgumentException("At least one build system is required");
    if (pids < 16 || timeoutSeconds < 1 || outputBytes < 1024) throw new IllegalArgumentException("Sandbox limits are invalid");
    Instant now = Instant.now();
    TestSandboxProfile existing = id == null ? null : profiles.findById(id)
        .orElseThrow(() -> new IllegalArgumentException("Sandbox profile not found"));
    Map<String, String> variables = CommandEnvironment.parse(environmentText);
    if (existing != null) {
      // An unchanged masked value keeps the stored value of the same name.
      Map<String, String> current = environment(existing);
      variables.replaceAll((variable, value) ->
          MASK.equals(value) && current.containsKey(variable) ? current.get(variable) : value);
    }
    if (variables.containsValue(MASK)) {
      throw new IllegalArgumentException("Enter a value for each new environment variable");
    }
    String ciphertext = variables.isEmpty() ? null
        : cipher.encrypt(CommandEnvironment.format(variables));
    TestSandboxProfile profile = existing == null
        ? new TestSandboxProfile(UUID.randomUUID(), name, image, EnumSet.copyOf(buildSystems), cpus,
            memory, pids, timeoutSeconds, outputBytes, network, enabled, ciphertext, now)
        : existing;
    if (existing != null) profile.update(name, image, EnumSet.copyOf(buildSystems), cpus, memory,
        pids, timeoutSeconds, outputBytes, network, enabled, ciphertext, now);
    profiles.save(profile);
    // Only names are audited; values may contain proxy credentials.
    audit.record(actorEmail, "TEST_SANDBOX_PROFILE_SAVED", "TEST_SANDBOX_PROFILE", profile.getId(),
        "Saved test sandbox profile " + profile.getName()
            + (variables.isEmpty() ? "" : " with environment variables " + variables.keySet()));
    return profile;
  }

  /** What a project currently uses; without a saved setting it follows the default profile. */
  @Transactional(readOnly = true)
  public ProjectSandboxChoice projectChoice(UUID projectId) {
    return settings.findById(projectId)
        .map(value -> new ProjectSandboxChoice(value.profileId(), value.networkEnabled(),
            value.autoApprovePackageInstalls(), value.autoApproveTerminalCommands()))
        .orElseGet(() -> new ProjectSandboxChoice(
            profiles.findFirstByDefaultProfileTrueAndEnabledTrue()
                .map(TestSandboxProfile::getId).orElse(null), null, false, false));
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

  static TestSandboxProfileView view(
      TestSandboxProfile profile, boolean network, Map<String, String> environment) {
    return new TestSandboxProfileView(profile.getId(), profile.getName(), profile.getImage(),
        Set.copyOf(profile.buildSystems()), profile.getCpuLimit(), profile.getMemoryLimit(),
        profile.getPidsLimit(), Duration.ofSeconds(profile.getTimeoutSeconds()),
        profile.getOutputLimitBytes(), network, profile.isEnabled(), profile.isDefaultProfile(),
        environment);
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
