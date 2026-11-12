package tech.wenisch.petri.kubernetes;

/**
 * Whether and how the bundle of command line tools Petri ships is used in a workspace pod. The
 * bundle supplies git, python, go, curl, node and npm to pods whose image lacks them, for example
 * where no registry offers an image that has them all.
 */
public enum ToolchainMode {
  /** The bundle is not copied into the pod. */
  OFF,
  /** The bundle is copied; its tools come after the image's own on the PATH and fill the gaps. */
  FALLBACK,
  /** The bundle is copied; its tools come first on the PATH and replace the image's. */
  PREFER;

  public static final ToolchainMode DEFAULT = FALLBACK;

  public static ToolchainMode parse(String value) {
    if (value == null || value.isBlank()) return null;
    return valueOf(value.strip().toUpperCase(java.util.Locale.ROOT));
  }

  public boolean enabled() {
    return this != OFF;
  }
}
