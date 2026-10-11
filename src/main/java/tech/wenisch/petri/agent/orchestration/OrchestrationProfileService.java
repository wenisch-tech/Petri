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
  private static final int DEFAULT_TURNS = 20;
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
      if (command == null || command.role() == null || command.provider() == null) {
        throw new IllegalArgumentException("Role and provider are required");
      }
      ModelCredentialStatus credential = credentials.status(command.provider());
      if (!credential.configured()) throw new IllegalStateException(command.provider() + " credentials are not configured");
      String model = required(command.model(), "Model", 200);
      int turns = bounded(command.maxModelTurns(), DEFAULT_TURNS, 1, 100, "Maximum model turns");
      int output = bounded(command.maxOutputTokens(), credential.maxOutputTokens(), 16, 1_000_000, "Maximum output tokens");
      int context = bounded(command.contextWindowTokens(), credential.contextWindowTokens(), output + 2048, 10_000_000, "Context window");
      validateContextWindow(context, output);
      int attempts = bounded(command.maxAttempts(), DEFAULT_ATTEMPTS, 1, 10, "Maximum attempts");
      String addendum = optional(command.promptAddendum(), 20_000);
      OrchestrationProfile value = profiles.findByProjectIdAndUserIdAndRole(projectId, userId, command.role())
          .orElseGet(() -> new OrchestrationProfile(UUID.randomUUID(), projectId, userId, command.role(),
              command.provider(), model, turns, output, context, command.reasoningEffort(),
              command.textVerbosity(), attempts, addendum, now));
      value.replace(command.provider(), model, turns, output, context, command.reasoningEffort(),
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
      OrchestrationRoleProfileView value = override == null ? base : resolve(base, override);
      snapshots.save(new AgentWorkflowRoleSnapshot(UUID.randomUUID(), workflowId, value));
    }
  }

  private OrchestrationRoleProfileView resolve(
      OrchestrationRoleProfileView base, SaveOrchestrationRoleProfile command) {
    ModelProvider provider = command.provider() == null ? base.provider() : command.provider();
    ModelCredentialStatus credential = credentials.status(provider);
    if (!credential.configured()) throw new IllegalStateException(provider + " credentials are not configured");
    String model = command.model() == null ? base.model() : required(command.model(), "Model", 200);
    int turns = bounded(command.maxModelTurns(), base.maxModelTurns(), 1, 100, "Maximum model turns");
    int output = bounded(command.maxOutputTokens(), base.maxOutputTokens(), 16, 1_000_000, "Maximum output tokens");
    int context = bounded(command.contextWindowTokens(), base.contextWindowTokens(), output + 2048,
        10_000_000, "Context window");
    validateContextWindow(context, output);
    int attempts = bounded(command.maxAttempts(), base.maxAttempts(), 1, 10, "Maximum attempts");
    return new OrchestrationRoleProfileView(base.role(), provider, model, turns, output, context,
        command.reasoningEffort() == null ? base.reasoningEffort() : command.reasoningEffort(),
        command.textVerbosity() == null ? base.textVerbosity() : command.textVerbosity(), attempts,
        command.promptAddendum() == null ? base.promptAddendum() : optional(command.promptAddendum(), 20_000),
        false);
  }

  private OrchestrationRoleProfileView fallback(UUID projectId, UUID userId, AgentWorkflowRole role) {
    WorkspaceModelPreferenceView preference = preferences.get(projectId, userId);
    ModelProvider provider = preference != null && credentials.status(preference.provider()).configured()
        ? preference.provider() : defaultProvider();
    ModelCredentialStatus credential = credentials.status(provider);
    String model = preference != null && preference.provider() == provider ? preference.model() : credential.model();
    return new OrchestrationRoleProfileView(role, provider, model, DEFAULT_TURNS,
        credential.maxOutputTokens(), credential.contextWindowTokens(), credential.reasoningEffort(),
        credential.textVerbosity(), DEFAULT_ATTEMPTS, null, false);
  }

  private ModelProvider defaultProvider() {
    if (credentials.status(ModelProvider.OLLAMA).configured()) return ModelProvider.OLLAMA;
    for (ModelProvider value : ModelProvider.values()) if (credentials.status(value).configured()) return value;
    throw new IllegalStateException("No model provider is configured");
  }

  private static OrchestrationRoleProfileView view(OrchestrationProfile value, boolean persisted) {
    return new OrchestrationRoleProfileView(value.role(), value.provider(), value.model(),
        value.maxModelTurns(), value.maxOutputTokens(), value.contextWindowTokens(),
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
