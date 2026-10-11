package tech.wenisch.petri.agent.run;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.regex.Pattern;

/** Allows only plain Alpine package names; flags, URLs, paths and version pins are rejected. */
public final class PackageNamePolicy {
  public static final int MAX_PACKAGES = 20;
  private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9+._-]{0,63}");

  private PackageNamePolicy() {}

  public static List<String> validate(List<String> requested) {
    if (requested == null || requested.isEmpty()) {
      throw new IllegalArgumentException("At least one package name is required");
    }
    LinkedHashSet<String> names = new LinkedHashSet<>();
    for (String value : requested) {
      String name = value == null ? "" : value.strip();
      if (!NAME.matcher(name).matches()) {
        throw new IllegalArgumentException(
            "Invalid package name: only lowercase Alpine package names are allowed");
      }
      names.add(name);
    }
    if (names.size() > MAX_PACKAGES) {
      throw new IllegalArgumentException("At most " + MAX_PACKAGES + " packages per request");
    }
    return List.copyOf(names);
  }
}
