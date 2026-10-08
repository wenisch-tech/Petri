package tech.wenisch.petri.agent.tool;

public record ReplacementPreview(
    String path, String expectedSha256, String replacementSha256, String diff) {}
