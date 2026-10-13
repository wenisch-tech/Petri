package tech.wenisch.petri.mcp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;

/** Turns server and tool names into model-safe tool names: {@code mcp__<server>__<tool>}, 64 chars. */
public final class McpToolNaming {
  public static final String PREFIX = "mcp__";
  private static final int MAX_LENGTH = 64;

  private McpToolNaming() {}

  public static boolean isMcpTool(String name) {
    return name != null && name.startsWith(PREFIX);
  }

  /** Lower-case letters, digits and single dashes; never empty. */
  static String slug(String value) {
    String slug = value == null ? "" : value.toLowerCase(Locale.ROOT)
        .replaceAll("[^a-z0-9_]+", "-").replaceAll("^-+|-+$", "");
    return slug.isEmpty() ? "x" : slug;
  }

  /** A unique exposed name within {@code taken}; the caller adds the result to {@code taken}. */
  static String expose(String serverName, String toolName, Set<String> taken) {
    String server = slug(serverName);
    String tool = toolName == null ? "" : toolName.replaceAll("[^A-Za-z0-9_-]+", "_");
    if (tool.isEmpty()) tool = "tool";
    String name = PREFIX + server + "__" + tool;
    if (name.length() > MAX_LENGTH || taken.contains(name)) {
      String hash = shortHash(serverName + "\u0000" + toolName);
      int room = MAX_LENGTH - hash.length() - 1;
      String base = name.length() > room ? name.substring(0, room) : name;
      name = base + "_" + hash;
    }
    return name;
  }

  private static String shortHash(String value) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest, 0, 4);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }
}
