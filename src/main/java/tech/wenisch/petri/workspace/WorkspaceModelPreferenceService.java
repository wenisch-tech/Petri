package tech.wenisch.petri.workspace;

import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.agent.model.ModelProvider;
import tech.wenisch.petri.audit.AuditLogService;
import tech.wenisch.petri.project.ProjectRepository;
import tech.wenisch.petri.security.AppUserRepository;

@Service
public class WorkspaceModelPreferenceService {
  private final WorkspaceModelPreferenceRepository preferences;
  private final ProjectRepository projects;
  private final AppUserRepository users;
  private final AuditLogService audit;

  public WorkspaceModelPreferenceService(
      WorkspaceModelPreferenceRepository preferences,
      ProjectRepository projects,
      AppUserRepository users,
      AuditLogService audit) {
    this.preferences = preferences;
    this.projects = projects;
    this.users = users;
    this.audit = audit;
  }

  @Transactional(readOnly = true)
  public WorkspaceModelPreferenceView get(UUID projectId, UUID userId) {
    return preferences.findByProjectIdAndUserId(projectId, userId)
        .map(value -> new WorkspaceModelPreferenceView(value.provider(), value.model(), true))
        .orElse(null);
  }

  @Transactional
  public WorkspaceModelPreferenceView save(
      UUID projectId, UUID userId, String actorEmail, ModelProvider provider, String model) {
    if (!projects.existsById(projectId)) throw new IllegalArgumentException("Unknown project");
    if (!users.existsById(userId)) throw new IllegalArgumentException("Unknown user");
    if (provider == null) throw new IllegalArgumentException("Model provider is required");
    if (model == null || model.isBlank() || model.length() > 200) {
      throw new IllegalArgumentException("Model is required and must be at most 200 characters");
    }
    Instant now = Instant.now();
    WorkspaceModelPreference preference = preferences.findByProjectIdAndUserId(projectId, userId)
        .orElseGet(() -> new WorkspaceModelPreference(
            UUID.randomUUID(), projectId, userId, provider, model.strip(), now));
    preference.replace(provider, model.strip(), now);
    preferences.save(preference);
    audit.record(actorEmail, "WORKSPACE_MODEL_PREFERENCE_SAVED", "AGENT_PROJECT", projectId,
        "Selected " + provider + " model " + model.strip());
    return new WorkspaceModelPreferenceView(provider, model.strip(), true);
  }
}
