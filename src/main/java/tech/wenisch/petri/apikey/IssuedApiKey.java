package tech.wenisch.petri.apikey;

import java.time.Instant;
import java.util.UUID;

public record IssuedApiKey(UUID id, String name, String rawKey, String keyIdentifier, Instant expiresAt) {}
