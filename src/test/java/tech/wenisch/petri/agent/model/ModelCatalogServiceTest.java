package tech.wenisch.petri.agent.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tech.wenisch.petri.audit.AuditLogService;
import tech.wenisch.petri.security.AppUser;
import tech.wenisch.petri.security.AppUserRepository;
import tools.jackson.databind.ObjectMapper;

class ModelCatalogServiceTest {
  private final ModelProviderInstanceRepository instances =
      mock(ModelProviderInstanceRepository.class);
  private final ModelProviderModelRepository models = mock(ModelProviderModelRepository.class);
  private final ModelCredentialService credentials = mock(ModelCredentialService.class);
  private final OpenAiCompatibleTransport transport = mock(OpenAiCompatibleTransport.class);
  private final AppUserRepository users = mock(AppUserRepository.class);
  private final ModelCatalogService service = new ModelCatalogService(
      instances, models, credentials, transport, users, mock(AuditLogService.class),
      new ObjectMapper());

  private final AppUser admin = new AppUser(UUID.randomUUID(), "admin@petri.local", "x", "INSTANCE_ADMIN");
  private final AppUser alice = new AppUser(UUID.randomUUID(), "alice@petri.local", "x", "USER");

  private ModelProviderInstance globalInstance() {
    UUID id = UUID.randomUUID();
    ModelProviderInstance instance =
        new ModelProviderInstance(id, ModelProvider.LITELLM, null, admin.getId(), Instant.now());
    instance.configure("Team", "http://x/v1", "default-model", 4096, 32768, null, null, true,
        admin.getId(), Instant.now());
    when(instances.findById(id)).thenReturn(Optional.of(instance));
    when(credentials.visibleInstance(id, admin.getId())).thenReturn(instance);
    when(credentials.visibleInstance(id, alice.getId())).thenReturn(instance);
    return instance;
  }

  @Test
  void parsesOpenAiOllamaAndBareListShapesAndSkipsNonChatModels() {
    var openAi = service.parse(("{\"data\":[{\"id\":\"gpt-5\"},{\"id\":\"text-embedding-3-small\"},"
        + "{\"id\":\"whisper-1\"},{\"id\":\"gpt-5\"}]}").getBytes(StandardCharsets.UTF_8));
    assertThat(openAi).containsExactly("gpt-5");

    var ollama = service.parse("{\"models\":[{\"name\":\"qwen3-coder\"}]}".getBytes(StandardCharsets.UTF_8));
    assertThat(ollama).containsExactly("qwen3-coder");

    var bare = service.parse("[\"b\",\"a\"]".getBytes(StandardCharsets.UTF_8));
    assertThat(bare).containsExactly("a", "b");

    assertThatThrownBy(() -> service.parse("{\"error\":\"nope\"}".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void refreshReplacesDiscoveredModelsAndIsAdminOnlyForGlobalInstances() {
    ModelProviderInstance instance = globalInstance();
    when(users.findByEmailIgnoreCase("admin@petri.local")).thenReturn(Optional.of(admin));
    when(users.findByEmailIgnoreCase("alice@petri.local")).thenReturn(Optional.of(alice));
    when(transport.get(any(), any())).thenReturn(
        "{\"data\":[{\"id\":\"m1\"},{\"id\":\"m2\"}]}".getBytes(StandardCharsets.UTF_8));

    assertThat(service.refresh(instance.id(), "admin@petri.local")).isEqualTo(2);
    verify(models).deleteByInstanceIdAndCustomFalse(instance.id());
    verify(models, org.mockito.Mockito.times(2)).save(any(ModelProviderModel.class));
    assertThat(instance.modelsRefreshedAt()).isNotNull();

    assertThatThrownBy(() -> service.refresh(instance.id(), "alice@petri.local"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void usersAddPersonalModelsButOnlyAdminsShareThem() {
    ModelProviderInstance instance = globalInstance();
    when(users.findByEmailIgnoreCase("admin@petri.local")).thenReturn(Optional.of(admin));
    when(users.findByEmailIgnoreCase("alice@petri.local")).thenReturn(Optional.of(alice));
    when(models.findByInstanceIdIn(List.of(instance.id()))).thenReturn(List.of());

    service.addCustom(instance.id(), " my-model ", false, "alice@petri.local");
    var saved = org.mockito.ArgumentCaptor.forClass(ModelProviderModel.class);
    verify(models).save(saved.capture());
    assertThat(saved.getValue().modelId()).isEqualTo("my-model");
    assertThat(saved.getValue().ownerId()).isEqualTo(alice.getId());
    assertThat(saved.getValue().custom()).isTrue();

    assertThatThrownBy(() -> service.addCustom(instance.id(), "x", true, "alice@petri.local"))
        .isInstanceOf(IllegalArgumentException.class);
    service.addCustom(instance.id(), "shared-model", true, "admin@petri.local");
    verify(models, org.mockito.Mockito.times(2)).save(any(ModelProviderModel.class));
  }

  @Test
  void addingTheDefaultModelAgainDoesNothing() {
    ModelProviderInstance instance = globalInstance();
    when(users.findByEmailIgnoreCase("alice@petri.local")).thenReturn(Optional.of(alice));
    service.addCustom(instance.id(), "default-model", false, "alice@petri.local");
    verify(models, never()).save(any());
  }

  @Test
  void removingSomeoneElsesModelIsRejected() {
    when(users.findByEmailIgnoreCase("alice@petri.local")).thenReturn(Optional.of(alice));
    ModelProviderModel row = new ModelProviderModel(
        UUID.randomUUID(), UUID.randomUUID(), "x", true, UUID.randomUUID(), Instant.now());
    when(models.findById(row.id())).thenReturn(Optional.of(row));
    assertThatThrownBy(() -> service.removeCustom(row.id(), "alice@petri.local"))
        .isInstanceOf(IllegalArgumentException.class);
    verify(models, never()).delete(any());
  }
}
