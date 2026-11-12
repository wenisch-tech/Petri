package tech.wenisch.petri.workspace;

/**
 * Bytes of a regular, non-symbolic-link file inside a worktree. {@code bytes} is null when the file
 * is missing, not a regular file or escapes the worktree; {@code tooLarge} is set when it exceeds the
 * requested limit (and {@code bytes} is then null as well).
 */
public record RawFile(byte[] bytes, boolean tooLarge) {
  public static RawFile absent() {
    return new RawFile(null, false);
  }

  public static RawFile oversized() {
    return new RawFile(null, true);
  }

  public boolean present() {
    return bytes != null;
  }
}
