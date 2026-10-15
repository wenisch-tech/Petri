package tech.wenisch.petri.packages;

public enum PackageRemoteMode {
  /** Used instead of the tool's public default (and instead of the instance's remotes). */
  REPLACE("Replace the default"),
  /** Used in addition to the default. */
  ADD("Add to the default");

  private final String label;

  PackageRemoteMode(String label) { this.label = label; }

  public String getLabel() { return label; }
}
