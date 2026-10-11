package tech.wenisch.petri.git.provider;

public record ProviderRepository(
    String id,
    String name,
    String fullName,
    String cloneUrl,
    String webUrl,
    String defaultBranch,
    String visibility,
    boolean archived,
    boolean canWrite,
    boolean alreadyImported) {
  public ProviderRepository withAlreadyImported(boolean value) {
    return new ProviderRepository(id, name, fullName, cloneUrl, webUrl, defaultBranch,
        visibility, archived, canWrite, value);
  }
}
