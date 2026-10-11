package tech.wenisch.petri.git.provider;

import java.time.Instant;

public record ProviderToken(
    String accessToken,
    String refreshToken,
    Instant accessExpiresAt,
    Instant refreshExpiresAt,
    String scopes) {}
