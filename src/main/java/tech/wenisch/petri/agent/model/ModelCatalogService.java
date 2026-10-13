package tech.wenisch.petri.agent.model;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.audit.AuditLogService;
import tech.wenisch.petri.security.AppUser;
import tech.wenisch.petri.security.AppUserRepository;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The models each provider instance offers. They are discovered from the endpoint's model listing
 * when an administrator (global instance) or owner (personal instance) refreshes, and can be
 * extended with free-text model IDs: shared ones by an administrator, personal ones by any user
 * who can select the instance.
 */
@Service
public class ModelCatalogService {
  private static final String ADMIN_ROLE = "INSTANCE_ADMIN";
  private static final String TARGET_TYPE = "MODEL_PROVIDER_INSTANCE";
  private static final int MAX_MODELS = 500;
  private static final int MAX_MODEL_ID_LENGTH = 200;
  /** Listings such as OpenAI's include models that cannot hold a chat. */
  private static final Pattern NON_CHAT = Pattern.compile(
      "(?i)embed|whisper|tts|dall-e|moderation|davinci-002|babbage|text-to-speech|rerank");

  private final ModelProviderInstanceRepository instances;
  private final ModelProviderModelRepository models;
  private final ModelCredentialService credentials;
  private final OpenAiCompatibleTransport transport;
  private final AppUserRepository users;
  private final AuditLogService auditLog;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  @Autowired
  public ModelCatalogService(
      ModelProviderInstanceRepository instances,
      ModelProviderModelRepository models,
      ModelCredentialService credentials,
      OpenAiCompatibleTransport transport,
      AppUserRepository users,
      AuditLogService auditLog,
      ObjectMapper objectMapper) {
    this(instances, models, credentials, transport, users, auditLog, objectMapper,
        Clock.systemUTC());
  }

  ModelCatalogService(
      ModelProviderInstanceRepository instances,
      ModelProviderModelRepository models,
      ModelCredentialService credentials,
      OpenAiCompatibleTransport transport,
      AppUserRepository users,
      AuditLogService auditLog,
      ObjectMapper objectMapper,
      Clock clock) {
    this.instances = instances;
    this.models = models;
    this.credentials = credentials;
    this.transport = transport;
    this.users = users;
    this.auditLog = auditLog;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  /** The catalogue of each given instance as the actor sees it; invisible instances are skipped. */
  @Transactional(readOnly = true)
  public Map<UUID, ModelCatalogView> views(String actorEmail, Collection<UUID> instanceIds) {
    AppUser actor = actor(actorEmail);
    Map<UUID, ModelCatalogView> views = new HashMap<>();
    if (instanceIds.isEmpty()) return views;
    Map<UUID, List<ModelProviderModel>> rows = new HashMap<>();
    for (ModelProviderModel row : models.findByInstanceIdIn(instanceIds)) {
      rows.computeIfAbsent(row.instanceId(), key -> new ArrayList<>()).add(row);
    }
    for (UUID id : instanceIds) {
      ModelProviderInstance instance = instances.findById(id).orElse(null);
      if (instance == null) continue;
      boolean admin = isAdmin(actor);
      boolean canRefresh = instance.global() ? admin : actor.getId().equals(instance.ownerId());
      List<AvailableModel> list = new ArrayList<>();
      list.add(new AvailableModel(null, instance.model(), false, false, false));
      for (ModelProviderModel row : rows.getOrDefault(id, List.of())) {
        boolean mine = actor.getId().equals(row.ownerId());
        if (row.ownerId() != null && !mine) continue;
        boolean removable = row.custom() && (mine || (row.ownerId() == null && admin));
        list.add(new AvailableModel(
            row.id(), row.modelId(), row.custom(), row.ownerId() != null, removable));
      }
      views.put(id, new ModelCatalogView(
          id, distinct(list), instance.modelsRefreshedAt(), canRefresh,
          instance.global() && admin));
    }
    return views;
  }

  /** Model IDs a user may choose per instance: the default, discovered, shared and their own. */
  @Transactional(readOnly = true)
  public Map<UUID, List<String>> modelIds(UUID userId, Collection<ModelCredentialStatus> statuses) {
    Map<UUID, List<String>> result = new HashMap<>();
    List<UUID> ids = statuses.stream().map(ModelCredentialStatus::id).toList();
    Map<UUID, List<ModelProviderModel>> rows = new HashMap<>();
    if (!ids.isEmpty()) {
      for (ModelProviderModel row : models.findByInstanceIdIn(ids)) {
        if (row.ownerId() != null && !row.ownerId().equals(userId)) continue;
        rows.computeIfAbsent(row.instanceId(), key -> new ArrayList<>()).add(row);
      }
    }
    for (ModelCredentialStatus status : statuses) {
      Set<String> values = new LinkedHashSet<>();
      if (status.model() != null) values.add(status.model());
      rows.getOrDefault(status.id(), List.of()).stream()
          .map(ModelProviderModel::modelId)
          .sorted(String.CASE_INSENSITIVE_ORDER)
          .forEach(values::add);
      result.put(status.id(), List.copyOf(values));
    }
    return result;
  }

  /** Re-lists the endpoint's models. Administrators for global instances, owners for personal. */
  @Transactional
  public int refresh(UUID instanceId, String actorEmail) {
    AppUser actor = actor(actorEmail);
    ModelProviderInstance instance = credentials.visibleInstance(instanceId, actor.getId());
    boolean allowed = instance.global() ? isAdmin(actor) : actor.getId().equals(instance.ownerId());
    if (!allowed) throw new IllegalArgumentException("You cannot refresh this provider's models");
    byte[] body = transport.get(new ModelCredentialRef(instanceId, actor.getId()), "/models");
    Set<String> found = parse(body);
    models.deleteByInstanceIdAndCustomFalse(instanceId);
    Instant now = Instant.now(clock);
    for (String modelId : found) {
      models.save(new ModelProviderModel(UUID.randomUUID(), instanceId, modelId, false, null, now));
    }
    instance.markModelsRefreshed(now);
    instances.save(instance);
    auditLog.record(
        actor.getEmail(), "MODEL_CATALOG_REFRESHED", TARGET_TYPE, instanceId,
        "Discovered " + found.size() + " models for " + instance.displayName());
    return found.size();
  }

  /**
   * Adds a free-text model ID. With {@code shared} (administrators, global instances) every user of
   * the instance sees it; otherwise only the actor does.
   */
  @Transactional
  public void addCustom(UUID instanceId, String modelId, boolean shared, String actorEmail) {
    AppUser actor = actor(actorEmail);
    ModelProviderInstance instance = credentials.visibleInstance(instanceId, actor.getId());
    String value = modelId == null ? "" : modelId.strip();
    if (value.isEmpty()) throw new IllegalArgumentException("Model ID is required");
    if (value.length() > MAX_MODEL_ID_LENGTH) throw new IllegalArgumentException("Model ID is too long");
    if (shared && !(instance.global() && isAdmin(actor))) {
      throw new IllegalArgumentException("Only administrators can add models for every user");
    }
    UUID owner = shared ? null : actor.getId();
    boolean present = value.equals(instance.model())
        || models.findByInstanceIdIn(List.of(instanceId)).stream().anyMatch(row ->
            row.modelId().equals(value)
                && (row.ownerId() == null || row.ownerId().equals(actor.getId())));
    if (present) return;
    models.save(new ModelProviderModel(
        UUID.randomUUID(), instanceId, value, true, owner, Instant.now(clock)));
    auditLog.record(
        actor.getEmail(), "MODEL_CATALOG_MODEL_ADDED", TARGET_TYPE, instanceId,
        "Added model " + value + " to " + instance.displayName());
  }

  /** Removes a custom model: its owner may, and administrators may remove shared ones. */
  @Transactional
  public void removeCustom(UUID modelRowId, String actorEmail) {
    AppUser actor = actor(actorEmail);
    ModelProviderModel row = models.findById(modelRowId)
        .filter(ModelProviderModel::custom)
        .orElseThrow(() -> new IllegalArgumentException("Unknown model"));
    boolean allowed = row.ownerId() == null ? isAdmin(actor) : actor.getId().equals(row.ownerId());
    if (!allowed) throw new IllegalArgumentException("Unknown model");
    models.delete(row);
    auditLog.record(
        actor.getEmail(), "MODEL_CATALOG_MODEL_REMOVED", TARGET_TYPE, row.instanceId(),
        "Removed model " + row.modelId());
  }

  /** Reads model IDs from the shapes OpenAI, LiteLLM, Ollama and compatible servers return. */
  Set<String> parse(byte[] body) {
    JsonNode root;
    try {
      root = objectMapper.readTree(body);
    } catch (RuntimeException exception) {
      throw new IllegalStateException("The endpoint did not return a model list", exception);
    }
    JsonNode list = root.isArray() ? root : root.path("data").isArray() ? root.path("data")
        : root.path("models");
    if (!list.isArray()) throw new IllegalStateException("The endpoint did not return a model list");
    List<String> ids = new ArrayList<>();
    for (JsonNode item : list) {
      String id = item.isTextual() ? item.asText() : firstText(item, "id", "model", "name");
      if (id == null) continue;
      id = id.strip();
      if (id.isEmpty() || id.length() > MAX_MODEL_ID_LENGTH || NON_CHAT.matcher(id).find()) continue;
      ids.add(id);
    }
    ids.sort(String.CASE_INSENSITIVE_ORDER);
    Set<String> unique = new LinkedHashSet<>(ids);
    return unique.size() <= MAX_MODELS
        ? unique
        : new LinkedHashSet<>(List.copyOf(unique).subList(0, MAX_MODELS));
  }

  private static String firstText(JsonNode node, String... fields) {
    for (String field : fields) {
      JsonNode value = node.path(field);
      if (value.isTextual()) return value.asText();
    }
    return null;
  }

  private static List<AvailableModel> distinct(List<AvailableModel> values) {
    Map<String, AvailableModel> byId = new java.util.LinkedHashMap<>();
    for (AvailableModel value : values) byId.merge(value.modelId(), value, (first, second) ->
        first.id() == null && second.id() != null ? second : first);
    return byId.values().stream()
        .sorted(Comparator.comparing(AvailableModel::modelId, String.CASE_INSENSITIVE_ORDER))
        .toList();
  }

  private boolean isAdmin(AppUser actor) { return ADMIN_ROLE.equals(actor.getRole()); }

  private AppUser actor(String actorEmail) {
    return users.findByEmailIgnoreCase(actorEmail)
        .orElseThrow(() -> new IllegalArgumentException("Unknown user"));
  }
}
