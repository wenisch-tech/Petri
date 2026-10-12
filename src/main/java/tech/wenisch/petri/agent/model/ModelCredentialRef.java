package tech.wenisch.petri.agent.model;

import java.util.UUID;

/**
 * Selects the credentials for one model call: which configured instance to use and on whose behalf,
 * so that the caller's own API key is preferred over the instance's shared key.
 */
public record ModelCredentialRef(UUID instanceId, UUID userId) {}
