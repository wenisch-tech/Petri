package tech.wenisch.petri.apikey;

import java.time.LocalDate;

public record CreateApiKeyCommand(String name, LocalDate expiresOn) {}
