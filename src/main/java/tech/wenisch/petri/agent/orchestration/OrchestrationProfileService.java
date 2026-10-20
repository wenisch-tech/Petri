package tech.wenisch.petri.agent.orchestration;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.agent.model.ModelCredentialService;
import tech.wenisch.petri.agent.model.ModelCredentialStatus;
import tech.wenisch.petri.agent.model.ModelProvider;
import tech.wenisch.petri.audit.AuditLogService;
import tech.wenisch.petri.project.ProjectRepository;
import tech.wenisch.petri.workspace.WorkspaceModelPreferenceService;
import tech.wenisch.petri.workspace.WorkspaceModelPreferenceView;

@Service
public class OrchestrationProfileService {
  private static final int DEFAULT_TURNS = 50;
  private static final int DEFAULT_IMPLEMENTER_TURNS = 90;
  private static final int DEFAULT_ATTEMPTS = 3;
  private final OrchestrationProfileRepository profiles;
  private final AgentWorkflowRoleSnapshotRepository snapshots;
  private final ModelCredentialService credentials;
  private final WorkspaceModelPreferenceService preferences;
  private final ProjectRepository projects;
  private final AuditLogService audit;

  public OrchestrationProfileService(
      OrchestrationProfileRepository profiles,
      AgentWorkflowRoleSnapshotRepository snapshots,
      ModelCredentialService credentials,
      WorkspaceModelPreferenceService preferences,
      ProjectRepository projects,
      AuditLogService audit) {
    this.profiles = profiles; this.snapshots = snapshots; this.credentials = credentials;
    this.preferences = preferences; this.projects = projects; this.audit = audit;
  }

  @Transactional(readOnly = true)
  public List<OrchestrationRoleProfileView> get(UUID projectId, UUID userId) {
    requireProject(projectId);
    Map<AgentWorkflowRole, OrchestrationProfile> stored = new EnumMap<>(AgentWorkflowRole.class);
    profiles.findByProjectIdAndUserIdOrderByRoleAsc(projectId, userId)
        .forEach(value -> stored.put(value.role(), value));
    List<OrchestrationRoleProfileView> result = new ArrayList<>();
    for (AgentWorkflowRole role : AgentWorkflowRole.values()) {
      OrchestrationProfile value = stored.get(role);
      result.add(value == null ? fallback(projectId, userId, role) : view(value, true));
    }
    return result;
  }

  @Transactional
  public List<OrchestrationRoleProfileView> save(
      UUID projectId, UUID userId, String actor, List<SaveOrchestrationRoleProfile> commands) {
    requireProject(projectId);
    if (commands == null || commands.isEmpty()) throw new IllegalArgumentException("At least one role profile is required");
    Instant now = Instant.now();
    for (SaveOrchestrationRoleProfile command : commands) {
      if (command == null || command.role() == null
          || (command.provider() == null && command.providerInstanceId() == null)) {
        throw new IllegalArgumentException("Role and provider are required");
      }
      ModelCredentialStatus credential = credentials.resolve(
          userId, command.provider(), command.providerInstanceId());
      String model = required(command.model(), "Model", 200);
      int turns = turns(command.maxModelTurns(),
          command.role() == AgentWorkflowRole.IMPLEMENTER ? DEFAULT_IMPLEMENTER_TURNS : DEFAULT_TURNS);
      int output = bounded(command.maxOutputTokens(), credential.maxOutputTokens(), 16, 1_000_000, "Maximum output tokens");
      int context = bounded(command.contextWindowTokens(), credential.contextWindowTokens(), output + 2048, 10_000_000, "Context window");
      validateContextWindow(context, output);
      int attempts = bounded(command.maxAttempts(), DEFAULT_ATTEMPTS, 1, 10, "Maximum attempts");
      String addendum = optional(command.promptAddendum(), 20_000);
      OrchestrationProfile value = profiles.findByProjectIdAndUserIdAndRole(projectId, userId, command.role())
          .orElseGet(() -> new OrchestrationProfile(UUID.randomUUID(), projectId, userId, command.role(),
              credential.provider(), credential.id(), model, turns, output, context,
              command.reasoningEffort(), command.textVerbosity(), attempts, addendum, now));
      value.replace(credential.provider(), credential.id(), model, turns, output, context,
          command.reasoningEffort(),
          command.textVerbosity(), attempts, addendum, now);
      profiles.save(value);
    }
    audit.record(actor, "ORCHESTRATION_PROFILE_SAVED", "AGENT_PROJECT", projectId,
        "Updated gated agent role profiles");
    return get(projectId, userId);
  }

  @Transactional
  void snapshot(UUID workflowId, UUID projectId, UUID userId,
      List<SaveOrchestrationRoleProfile> overrides) {
    Map<AgentWorkflowRole, SaveOrchestrationRoleProfile> supplied = new EnumMap<>(AgentWorkflowRole.class);
    if (overrides != null) {
      for (SaveOrchestrationRoleProfile override : overrides) {
        if (override == null || override.role() == null || supplied.put(override.role(), override) != null) {
          throw new IllegalArgumentException("Task role overrides must have unique roles");
        }
      }
    }
    for (OrchestrationRoleProfileView base : get(projectId, userId)) {
      SaveOrchestrationRoleProfile override = supplied.get(base.role());
      OrchestrationRoleProfileView value = override == null ? base : resolve(base, override, userId);
      snapshots.save(new AgentWorkflowRoleSnapshot(UUID.randomUUID(), workflowId, value));
    }
  }

  private OrchestrationRoleProfileView resolve(
      OrchestrationRoleProfileView base, SaveOrchestrationRoleProfile command, UUID userId) {
    boolean overridesProvider = command.provider() != null || command.providerInstanceId() != null;
    ModelCredentialStatus credential = overridesProvider
        ? credentials.resolve(userId, command.provider(), command.providerInstanceId())
        : credentials.resolve(userId, base.provider(), base.providerInstanceId());
    ModelProvider provider = credential.provider();
    String model = command.model() == null ? base.model() : required(command.model(), "Model", 200);
    int turns = turns(command.maxModelTurns(), base.maxModelTurns());
    int output = bounded(command.maxOutputTokens(), base.maxOutputTokens(), 16, 1_000_000, "Maximum output tokens");
    int context = bounded(command.contextWindowTokens(), base.contextWindowTokens(), output + 2048,
        10_000_000, "Context window");
    validateContextWindow(context, output);
    int attempts = bounded(command.maxAttempts(), base.maxAttempts(), 1, 10, "Maximum attempts");
    return new OrchestrationRoleProfileView(base.role(), provider, credential.id(), model, turns,
        output, context,
        command.reasoningEffort() == null ? base.reasoningEffort() : command.reasoningEffort(),
        command.textVerbosity() == null ? base.textVerbosity() : command.textVerbosity(), attempts,
        command.promptAddendum() == null ? base.promptAddendum() : optional(command.promptAddendum(), 20_000),
        false);
  }

  private OrchestrationRoleProfileView fallback(UUID projectId, UUID userId, AgentWorkflowRole role) {
    WorkspaceModelPreferenceView preference = preferences.get(projectId, userId);
    List<ModelCredentialStatus> usable = credentials.usable(userId);
    if (usable.isEmpty()) throw new IllegalStateException("No model provider is configured");
    ModelCredentialStatus preferred = preference == null ? null : usable.stream()
        .filter(value -> preference.providerInstanceId() != null
            ? value.id().equals(preference.providerInstanceId())
            : value.provider() == preference.provider())
        .findFirst().orElse(null);
    ModelCredentialStatus chosen = preferred != null ? preferred : usable.stream()
        .filter(value -> value.provider() == ModelProvider.OLLAMA).findFirst()
        .orElse(usable.getFirst());
    String model = preferred != null ? preference.model() : chosen.model();
    return new OrchestrationRoleProfileView(role, chosen.provider(), chosen.id(), model,
        role == AgentWorkflowRole.IMPLEMENTER ? DEFAULT_IMPLEMENTER_TURNS : DEFAULT_TURNS, chosen.maxOutputTokens(), chosen.contextWindowTokens(),
        chosen.reasoningEffort(), chosen.textVerbosity(), DEFAULT_ATTEMPTS, null, false);
  }

  private static OrchestrationRoleProfileView view(OrchestrationProfile value, boolean persisted) {
    return new OrchestrationRoleProfileView(value.role(), value.provider(),
        value.providerInstanceId(), value.model(), value.maxModelTurns(), value.maxOutputTokens(), value.contextWindowTokens(),
        value.reasoningEffort(), value.textVerbosity(), value.maxAttempts(), value.promptAddendum(), persisted);
  }
  private void requireProject(UUID id) { if (!projects.existsById(id)) throw new IllegalArgumentException("Unknown project"); }
  private static String required(String value, String label, int max) {
    if (value == null || value.isBlank() || value.length() > max) throw new IllegalArgumentException(label + " is required and too long");
    return value.strip();
  }
  private static String optional(String value, int max) {
    if (value == null || value.isBlank()) return null;
    if (value.length() > max) throw new IllegalArgumentException("Prompt addendum is too long");
    return value.strip();
  }
  /** A turn budget of 1..100, or -1 for no limit. */
  private static int turns(Integer value, int fallback) {
    int result = value == null ? fallback : value;
    if (result != -1 && (result < 1 || result > 100)) {
      throw new IllegalArgumentException("Maximum model turns must be between 1 and 100, or -1 for no limit");
    }
    return result;
  }

  private static int bounded(Integer value, Integer fallback, int min, int max, String label) {
    int result = value == null ? fallback : value;
    if (result < min || result > max) throw new IllegalArgumentException(label + " must be between " + min + " and " + max);
    return result;
  }
  private static void validateContextWindow(int context, int output) {
    if (Math.floor(context * 0.85) < output + 2048) {
      throw new IllegalArgumentException(
          "Context window must leave at least 2048 input tokens after the safety reserve");
    }
  }
}
