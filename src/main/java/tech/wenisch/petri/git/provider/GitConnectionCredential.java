package tech.wenisch.petri.git.provider;

public record GitConnectionCredential(
    String username,
    String token,
    GitConnectionAuthMethod authMethod,
    String login) {}
