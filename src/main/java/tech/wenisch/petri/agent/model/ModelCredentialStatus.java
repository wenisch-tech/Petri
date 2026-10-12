package tech.wenisch.petri.agent.model;

import java.time.Instant;
import java.util.UUID;

/**
 * What a viewer may know about a provider instance. Secrets never appear here; the key flags only
 * say whether a key exists and where it comes from.
 *
 * @param global true for administrator-managed instances, false for a user's own
 * @param hasApiKey a key is available to the viewer (their own, or the instance's shared key)
 * @param hasUserKey the viewer has stored their own key
 * @param usable configured, enabled and, where the kind needs one, a key is available
 */
public record ModelCredentialStatus(
    UUID id,
    ModelProvider provider,
    String displayName,
    boolean global,
    UUID ownerId,
    boolean enabled,
    boolean configured,
    boolean hasApiKey,
    boolean hasSharedKey,
    boolean hasUserKey,
    boolean keyRequired,
    boolean usable,
    String baseUrl,
    String model,
    Integer maxOutputTokens,
    Integer contextWindowTokens,
    ReasoningEffort reasoningEffort,
    TextVerbosity textVerbosity,
    Instant updatedAt) {}
