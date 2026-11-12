package tech.wenisch.petri.workspace;

/** Result of an lstat-style inspection of a host path; symbolic links are never followed. */
public record HostStat(boolean exists, Type type, long size, boolean executable) {
  public enum Type { FILE, DIRECTORY, SYMLINK, OTHER, MISSING }

  public static HostStat missing() {
    return new HostStat(false, Type.MISSING, 0, false);
  }

  public boolean isFile() {
    return type == Type.FILE;
  }

  public boolean isDirectory() {
    return type == Type.DIRECTORY;
  }

  public boolean isSymlink() {
    return type == Type.SYMLINK;
  }
}
