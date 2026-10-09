package tech.wenisch.petri.agent.model;

record ModelCredentialMaterial(
    ModelProvider provider, String apiKey, String baseUrl, String model) {}
