package tech.wenisch.petri.agent.skill;

public record SkillDocument(
    String name, String description, String content, SkillManifest manifest) {
  public SkillDocument {
    manifest = manifest == null ? SkillManifest.EMPTY : manifest;
  }

  public SkillDocument(String name, String description, String content) {
    this(name, description, content, SkillManifest.EMPTY);
  }
}
