package tech.wenisch.petri.apikey;

import java.time.Instant;
import java.util.UUID;

public record ApiKeyPrincipal(UUID id, String name, UUID createdBy, Instant expiresAt) {}
