package tech.wenisch.petri.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.wenisch.petri.agent.model.ModelClient;
import tech.wenisch.petri.agent.model.ModelClientRegistry;
import tech.wenisch.petri.agent.model.ModelCredentialService;
import tech.wenisch.petri.agent.model.ModelCredentialStatus;
import tech.wenisch.petri.agent.model.ModelProvider;
import tech.wenisch.petri.agent.model.ModelRequest;
import tech.wenisch.petri.agent.model.ModelResponse;
import tech.wenisch.petri.agent.skill.SkillService;
import tech.wenisch.petri.audit.AuditLogService;
import tech.wenisch.petri.config.PetriProperties;
import tech.wenisch.petri.security.AppUser;
import tech.wenisch.petri.security.AppUserRepository;
import tech.wenisch.petri.workspace.WorkspaceModelPreferenceService;
import tech.wenisch.petri.workspace.WorkspaceModelPreferenceView;

class CommitMessageModelSelectionTest {
  private final UUID userId = UUID.randomUUID();
  private final UUID projectId = UUID.randomUUID();
  private final UUID worktreeId = UUID.randomUUID();
  private final UUID ollamaId = UUID.randomUUID();
  private final UUID openAiId = UUID.randomUUID();

  private final WorkspaceGitService git = mock(WorkspaceGitService.class);
  private final ModelCredentialService models = mock(ModelCredentialService.class);
  private final WorkspaceModelPreferenceService preferences = mock(WorkspaceModelPreferenceService.class);
  private final ProjectWorktreeService worktrees = mock(ProjectWorktreeService.class);
  private final AppUserRepository users = mock(AppUserRepository.class);
  private final ModelClient openAi = mock(ModelClient.class);
  private final ModelClient ollama = mock(ModelClient.class);
  private CommitMessageService service;

  private static ModelCredentialStatus status(UUID id, ModelProvider provider, String model) {
    ModelCredentialStatus value = mock(ModelCredentialStatus.class);
    when(value.id()).thenReturn(id);
    when(value.provider()).thenReturn(provider);
    when(value.model()).thenReturn(model);
    return value;
  }

  @BeforeEach
  void wire() {
    SkillService skills = new SkillService(new PetriProperties(null, null, null,
        new PetriProperties.Agent(null, null, null, null, Path.of("skills").toString(), null)));
    when(openAi.provider()).thenReturn(ModelProvider.OPENAI);
    when(ollama.provider()).thenReturn(ModelProvider.OLLAMA);
    for (ModelClient client : List.of(openAi, ollama)) {
      when(client.respond(any())).thenReturn(
          new ModelResponse("r", "completed", "feat: add thing", List.of(), 1, 1));
    }
    when(users.findByEmailIgnoreCase("u@petri.local")).thenReturn(
        Optional.of(AppUser.oidcUser(userId, "u@petri.local", "USER")));
    ProjectWorktreeView worktree = mock(ProjectWorktreeView.class);
    when(worktree.projectId()).thenReturn(projectId);
    when(worktrees.get(worktreeId)).thenReturn(worktree);
    when(git.commitContext(any(), org.mockito.ArgumentMatchers.anyBoolean(),
        org.mockito.ArgumentMatchers.anyInt())).thenReturn(new WorkspaceGitService.CommitContext(
            List.of(new WorkspaceChangeView("A.java", null, WorkspaceChangeStatus.MODIFIED,
                true, false, false, 1, 0)), "+x", false, List.of()));
    List<ModelCredentialStatus> usable = List.of(
        status(openAiId, ModelProvider.OPENAI, "gpt-default"),
        status(ollamaId, ModelProvider.OLLAMA, "llama-default"));
    when(models.usable(userId)).thenReturn(usable);
    service = new CommitMessageService(git, skills, models,
        new ModelClientRegistry(List.of(openAi, ollama)), users, mock(AuditLogService.class),
        worktrees, preferences);
  }

  private ModelRequest sent(ModelClient client) {
    var captor = org.mockito.ArgumentCaptor.forClass(ModelRequest.class);
    org.mockito.Mockito.verify(client).respond(captor.capture());
    return captor.getValue();
  }

  @Test
  void usesTheModelTheWorkspaceChatIsConfiguredWith() {
    when(preferences.get(projectId, userId)).thenReturn(new WorkspaceModelPreferenceView(
        ModelProvider.OPENAI, openAiId, "gpt-chosen", true));

    String message = service.generate(worktreeId, false, "u@petri.local");

    assertThat(message).isEqualTo("feat: add thing");
    ModelRequest request = sent(openAi);
    assertThat(request.model()).isEqualTo("gpt-chosen");
    assertThat(request.credential().instanceId()).isEqualTo(openAiId);
    org.mockito.Mockito.verify(ollama, org.mockito.Mockito.never()).respond(any());
  }

  @Test
  void aSavedChoiceThatIsNoLongerUsableFallsBackLikeTheChatDoes() {
    when(preferences.get(projectId, userId)).thenReturn(new WorkspaceModelPreferenceView(
        ModelProvider.LITELLM, UUID.randomUUID(), "gone", true));

    service.generate(worktreeId, false, "u@petri.local");

    ModelRequest request = sent(ollama);
    assertThat(request.model()).isEqualTo("llama-default");
    assertThat(request.credential().instanceId()).isEqualTo(ollamaId);
  }

  @Test
  void withoutASavedChoiceTheDefaultModelOfTheProviderIsUsed() {
    when(preferences.get(projectId, userId)).thenReturn(null);

    service.generate(worktreeId, true, "u@petri.local");

    assertThat(sent(ollama).model()).isEqualTo("llama-default");
  }
}
