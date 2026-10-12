package tech.wenisch.petri.workspace;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tech.wenisch.petri.agent.model.ModelCredentialService;
import tech.wenisch.petri.agent.model.ModelCredentialStatus;
import tech.wenisch.petri.agent.model.ModelProvider;

/**
 * The model a user works with in a project's workspace: their last chosen provider and model for
 * that project while they can still call it, otherwise the chat's fallback (Ollama first, then any
 * usable provider). Used wherever Petri calls a model on the user's behalf outside a chat message,
 * such as commit messages and automations, so they follow the same choice.
 */
@Component
public class WorkspaceModelResolver {
  /** @param model model name to request, or null for the provider's default */
  public record Resolved(ModelCredentialStatus status, String model) {}

  private final ModelCredentialService models;
  private final WorkspaceModelPreferenceService preferences;

  public WorkspaceModelResolver(
      ModelCredentialService models, WorkspaceModelPreferenceService preferences) {
    this.models = models;
    this.preferences = preferences;
  }

  public Resolved resolve(UUID projectId, UUID userId) {
    List<ModelCredentialStatus> usable = models.usable(userId);
    WorkspaceModelPreferenceView preference = preferences.get(projectId, userId);
    if (preference != null) {
      for (ModelCredentialStatus candidate : usable) {
        boolean matches = preference.providerInstanceId() != null
            ? candidate.id().equals(preference.providerInstanceId())
            : candidate.provider() == preference.provider();
        if (matches) {
          String model = preference.model() != null && !preference.model().isBlank()
              ? preference.model() : candidate.model();
          return new Resolved(candidate, model);
        }
      }
    }
    ModelCredentialStatus fallback = usable.stream()
        .filter(value -> value.provider() == ModelProvider.OLLAMA).findFirst()
        .or(() -> usable.stream().findFirst())
        .orElseThrow(() -> new IllegalStateException("No usable model provider is available"));
    return new Resolved(fallback, fallback.model());
  }
}
