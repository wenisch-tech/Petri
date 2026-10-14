package tech.wenisch.petri.agent.run;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tech.wenisch.petri.workspace.WorkspacePaths;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Scans a worktree with Trivy (dependencies, secrets, misconfigurations) and returns a compact
 * summary. Trivy runs as a bounded subprocess in the same Landlock confinement as terminal
 * commands: it may read the worktree and write only its cache and a private scratch directory. The
 * vulnerability database is downloaded on first use into Petri's data volume.
 */
@Service
public class TrivyScanService {
  public static final Set<String> SCANNERS = Set.of("vuln", "secret", "misconfig");
  public static final List<String> SEVERITIES = List.of("CRITICAL", "HIGH", "MEDIUM", "LOW", "UNKNOWN");
  private static final int MAX_FINDINGS = 40;
  private static final int OUTPUT_LIMIT = 64 * 1024;
  private static final Duration TIMEOUT = Duration.ofMinutes(12);

  public record Finding(
      String type, String severity, String id, String title, String target, String pkg,
      String installed, String fixed, Integer line, String url) {}

  public record Result(
      String version, boolean complete, Map<String, Integer> bySeverity,
      Map<String, Integer> byType, int total, int fixable, List<Finding> findings,
      boolean truncated, String note) {}

  private final WorkspacePaths paths;
  private final LocalToolchain toolchain;
  private final Confinement confinement;
  private final ObjectMapper json;
  private final String command;

  TrivyScanService(
      WorkspacePaths paths, LocalToolchain toolchain, Confinement confinement, ObjectMapper json,
      @Value("${petri.trivy.command:trivy}") String command) {
    this.paths = paths;
    this.toolchain = toolchain;
    this.confinement = confinement;
    this.json = json;
    this.command = command == null || command.isBlank() ? "trivy" : command.strip();
  }

  /** The Trivy executable, or null when it is not installed. */
  Path executable() {
    Path direct = Path.of(command);
    if (direct.isAbsolute()) return Files.isExecutable(direct) ? direct : null;
    for (String directory : System.getenv().getOrDefault("PATH", "").split(":")) {
      if (directory.isBlank()) continue;
      Path candidate = Path.of(directory).resolve(command);
      if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return candidate;
    }
    return null;
  }

  public boolean available() {
    return executable() != null;
  }

  public Result scan(
      Path worktree, String subPath, List<String> scanners, List<String> severities,
      boolean ignoreUnfixed) throws IOException {
    Path trivy = executable();
    if (trivy == null) {
      throw new IllegalStateException("Trivy is not installed in this Petri instance. The "
          + "official Petri image includes it; for a custom image add the trivy binary to PATH "
          + "or set petri.trivy.command.");
    }
    Path root = paths.requireExistingPath(worktree);
    Path target = root;
    if (subPath != null && !subPath.isBlank() && !".".equals(subPath.strip())) {
      target = root.resolve(subPath.strip()).normalize();
      if (!target.startsWith(root) || !Files.isDirectory(target)) {
        throw new IllegalArgumentException("path must be a directory inside the project");
      }
      target = target.toRealPath();
      if (!target.startsWith(root.toRealPath())) {
        throw new IllegalArgumentException("path must be a directory inside the project");
      }
    }
    List<String> kinds = scanners == null || scanners.isEmpty() ? List.of("vuln", "secret") : scanners;
    for (String scanner : kinds) {
      if (!SCANNERS.contains(scanner)) throw new IllegalArgumentException("Unknown scanner " + scanner);
    }
    List<String> levels = severities == null || severities.isEmpty()
        ? List.of("CRITICAL", "HIGH", "MEDIUM") : severities;
    for (String level : levels) {
      if (!SEVERITIES.contains(level)) throw new IllegalArgumentException("Unknown severity " + level);
    }

    Path cache = paths.directory("trivy-cache");
    Path home = Files.createTempDirectory(paths.temporaryDirectory(), "petri-trivy-");
    try {
      Path report = home.resolve("report.json");
      List<String> argv = new ArrayList<>(List.of(
          trivy.toString(), "fs", "--quiet", "--no-progress", "--skip-version-check",
          "--format", "json", "--output", report.toString(),
          "--scanners", String.join(",", kinds),
          "--severity", String.join(",", levels),
          "--cache-dir", cache.toString(),
          "--timeout", "10m"));
      if (ignoreUnfixed) argv.add("--ignore-unfixed");
      argv.add(target.toString());
      Map<String, String> extra = new LinkedHashMap<>();
      extra.put("TRIVY_DISABLE_TELEMETRY", "true");
      extra.put("TRIVY_CACHE_DIR", cache.toString());
      List<String> wrapped = confinement.available()
          ? confinement.wrap(List.of(root, trivy.getParent()), List.of(cache, home), argv)
          : argv;
      TestCommandResult run = BoundedProcess.run(wrapped, root,
          toolchain.environment(home, extra), TIMEOUT, OUTPUT_LIMIT, "trivy");
      if (run.timedOut()) {
        throw new IllegalStateException("The scan did not finish within " + TIMEOUT.toMinutes()
            + " minutes. Scan a subdirectory with the path argument instead.");
      }
      if (run.exitCode() != 0 || !Files.isRegularFile(report)) {
        throw new IllegalStateException("Trivy failed (exit " + run.exitCode() + "): "
            + tail(run.output(), 1_200));
      }
      return parse(Files.readString(report), levels);
    } finally {
      deleteQuietly(home);
    }
  }

  Result parse(String report, List<String> requested) {
    JsonNode root;
    try {
      root = json.readTree(report);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Trivy returned a report that could not be read");
    }
    Map<String, Integer> bySeverity = new LinkedHashMap<>();
    for (String level : SEVERITIES) bySeverity.put(level, 0);
    Map<String, Integer> byType = new LinkedHashMap<>();
    List<Finding> findings = new ArrayList<>();
    int fixable = 0;
    for (JsonNode result : root.path("Results")) {
      String target = text(result, "Target");
      for (JsonNode vulnerability : result.path("Vulnerabilities")) {
        String fixed = text(vulnerability, "FixedVersion");
        if (fixed != null) fixable++;
        add(findings, bySeverity, byType, new Finding("vulnerability",
            severity(vulnerability), text(vulnerability, "VulnerabilityID"),
            text(vulnerability, "Title"), target, text(vulnerability, "PkgName"),
            text(vulnerability, "InstalledVersion"), fixed, null, text(vulnerability, "PrimaryURL")));
      }
      for (JsonNode secret : result.path("Secrets")) {
        add(findings, bySeverity, byType, new Finding("secret", severity(secret),
            text(secret, "RuleID"), text(secret, "Title"), target, null, null, null,
            secret.path("StartLine").isInt() ? secret.path("StartLine").asInt() : null, null));
      }
      for (JsonNode misconfiguration : result.path("Misconfigurations")) {
        JsonNode cause = misconfiguration.path("CauseMetadata");
        add(findings, bySeverity, byType, new Finding("misconfiguration",
            severity(misconfiguration), text(misconfiguration, "ID"), text(misconfiguration, "Title"),
            target, null, null, text(misconfiguration, "Resolution"),
            cause.path("StartLine").isInt() ? cause.path("StartLine").asInt() : null,
            text(misconfiguration, "PrimaryURL")));
      }
    }
    int total = findings.size();
    findings.sort(Comparator
        .comparingInt((Finding finding) -> SEVERITIES.indexOf(finding.severity()))
        .thenComparing(finding -> finding.fixed() == null)
        .thenComparing(finding -> finding.id() == null ? "" : finding.id()));
    boolean truncated = findings.size() > MAX_FINDINGS;
    List<Finding> shown = truncated ? List.copyOf(findings.subList(0, MAX_FINDINGS)) : List.copyOf(findings);
    String note = total == 0
        ? "Trivy found no " + String.join("/", requested).toLowerCase(Locale.ROOT)
            + " issues with the chosen scanners. This is not proof the project is secure."
        : null;
    String version = text(root.path("Trivy"), "Version");
    return new Result(version == null ? "unknown" : version,
        true, bySeverity, byType, total, fixable, shown, truncated, note);
  }

  private static void add(
      List<Finding> findings, Map<String, Integer> bySeverity, Map<String, Integer> byType,
      Finding finding) {
    findings.add(finding);
    bySeverity.merge(finding.severity(), 1, Integer::sum);
    byType.merge(finding.type(), 1, Integer::sum);
  }

  private static String severity(JsonNode node) {
    String value = text(node, "Severity");
    return value == null || !SEVERITIES.contains(value.toUpperCase(Locale.ROOT))
        ? "UNKNOWN" : value.toUpperCase(Locale.ROOT);
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.path(field);
    if (value.isMissingNode() || value.isNull()) return null;
    String text = value.asString();
    return text == null || text.isBlank() ? null : text;
  }

  private static String tail(String value, int max) {
    if (value == null) return "";
    String trimmed = value.strip();
    return trimmed.length() <= max ? trimmed : "…" + trimmed.substring(trimmed.length() - max);
  }

  private static void deleteQuietly(Path directory) {
    try (var walk = Files.walk(directory)) {
      walk.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
    } catch (IOException ignored) {
      // best effort
    }
  }
}
