package tech.wenisch.petri.agent.run;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Extra environment variables for commands Petri runs (terminal, tests, package installs).
 *
 * <p>Commands run with a scrubbed environment, so nothing is inherited implicitly except the proxy
 * variable family (forwarded unless {@code PETRI_COMMAND_ENV_FORWARD_PROXY=false}). Layers, lowest to
 * highest: forwarded proxy variables, {@code PETRI_COMMAND_ENV_<NAME>} instance variables, then the
 * sandbox profile's variables. Reserved names can never be set.
 */
@Component
public class CommandEnvironment {
  public static final String INSTANCE_PREFIX = "PETRI_COMMAND_ENV_";
  static final String FORWARD_PROXY = INSTANCE_PREFIX + "FORWARD_PROXY";
  static final int MAX_VARIABLES = 50;
  static final int MAX_VALUE_LENGTH = 4096;

  private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,127}");
  private static final Set<String> RESERVED = Set.of(
      "PATH", "HOME", "TMPDIR", "LD_LIBRARY_PATH", "LD_PRELOAD", "JAVA_HOME", "JAVA_TOOL_OPTIONS",
      "MAVEN_OPTS", "GRADLE_USER_HOME", "CI", "LC_ALL");
  private static final List<String> PROXY_NAMES = List.of(
      "http_proxy", "https_proxy", "ftp_proxy", "all_proxy", "no_proxy");

  private final Map<String, String> instance;

  public CommandEnvironment() {
    this(System.getenv());
  }

  public CommandEnvironment(Map<String, String> processEnvironment) {
    Map<String, String> merged = new LinkedHashMap<>();
    boolean forward = !"false".equalsIgnoreCase(processEnvironment.get(FORWARD_PROXY));
    if (forward) {
      for (String name : PROXY_NAMES) {
        String lower = processEnvironment.get(name);
        String upper = processEnvironment.get(name.toUpperCase(Locale.ROOT));
        String value = lower != null && !lower.isBlank() ? lower : upper;
        // Tools disagree on case (apk and curl read lower-case, Java tooling upper-case).
        if (value != null && !value.isBlank() && value.length() <= MAX_VALUE_LENGTH) {
          merged.put(name, value);
          merged.put(name.toUpperCase(Locale.ROOT), upper != null && !upper.isBlank() ? upper : value);
        }
      }
    }
    processEnvironment.forEach((name, value) -> {
      if (!name.startsWith(INSTANCE_PREFIX) || name.equals(FORWARD_PROXY)) return;
      String stripped = name.substring(INSTANCE_PREFIX.length());
      if (value != null && isAllowedName(stripped) && value.length() <= MAX_VALUE_LENGTH) {
        merged.put(stripped, value);
      }
    });
    this.instance = Map.copyOf(merged);
  }

  /** No forwarding and no instance variables; for tests and callers without configuration. */
  public static CommandEnvironment none() {
    return new CommandEnvironment(Map.of());
  }

  /** Forwarded and instance variables overlaid with the profile's, in precedence order. */
  public Map<String, String> extra(Map<String, String> profileVariables) {
    Map<String, String> result = new LinkedHashMap<>(instance);
    if (profileVariables != null) {
      profileVariables.forEach((name, value) -> {
        if (isAllowedName(name) && value != null) result.put(name, value);
      });
    }
    return result;
  }

  /** The {@code --env K=V} arguments for a container. */
  public List<String> containerEnvArguments(Map<String, String> profileVariables) {
    List<String> arguments = new java.util.ArrayList<>();
    extra(profileVariables).forEach((name, value) -> {
      arguments.add("--env");
      arguments.add(name + "=" + value);
    });
    return arguments;
  }

  static boolean isAllowedName(String name) {
    return name != null && NAME.matcher(name).matches()
        && !RESERVED.contains(name.toUpperCase(Locale.ROOT))
        && !name.toUpperCase(Locale.ROOT).startsWith("PETRI_");
  }

  /** Parses {@code KEY=VALUE} lines ({@code #} comments allowed), rejecting invalid entries. */
  public static Map<String, String> parse(String text) {
    Map<String, String> result = new LinkedHashMap<>();
    if (text == null) return result;
    for (String raw : text.split("\\R")) {
      String line = raw.strip();
      if (line.isEmpty() || line.startsWith("#")) continue;
      int equals = line.indexOf('=');
      if (equals <= 0) throw new IllegalArgumentException("Environment variable must be NAME=VALUE: " + line);
      String name = line.substring(0, equals).strip();
      String value = line.substring(equals + 1);
      if (!NAME.matcher(name).matches()) {
        throw new IllegalArgumentException("Invalid environment variable name: " + name);
      }
      if (!isAllowedName(name)) {
        throw new IllegalArgumentException("Environment variable name is reserved: " + name);
      }
      if (value.length() > MAX_VALUE_LENGTH) {
        throw new IllegalArgumentException("Environment variable value is too long: " + name);
      }
      if (value.chars().anyMatch(c -> Character.isISOControl(c))) {
        throw new IllegalArgumentException("Environment variable value has control characters: " + name);
      }
      result.put(name, value);
    }
    if (result.size() > MAX_VARIABLES) {
      throw new IllegalArgumentException("At most " + MAX_VARIABLES + " environment variables are allowed");
    }
    return result;
  }

  public static String format(Map<String, String> variables) {
    StringBuilder text = new StringBuilder();
    variables.forEach((name, value) -> text.append(name).append('=').append(value).append('\n'));
    return text.toString();
  }
}
