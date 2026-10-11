package tech.wenisch.petri.git.provider;

import java.time.Instant;
import java.util.UUID;

public record GitAccountConnectionView(
    UUID id,
    UUID providerInstanceId,
    String providerName,
    GitProviderType providerType,
    GitConnectionAuthMethod authMethod,
    String login,
    String displayName,
    GitConnectionStatus status,
    String grantedScopes,
    Instant expiresAt,
    boolean insecureTransport,
    boolean forgejoOauthWarning) {}
