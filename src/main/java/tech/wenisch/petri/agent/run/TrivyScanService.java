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
      boolean truncated, String note, List<Resolution> dependencyResolution) {
    Result withResolution(List<Resolution> value) {
      return new Result(version, complete, bySeverity, byType, total, fixable, findings, truncated,
          note, value);
    }
  }

  /** What was done for one package manager of the project before scanning. */
  public record Resolution(String ecosystem, String directory, String status, String note) {}

  private final WorkspacePaths paths;
  private final DependencyResolver resolver;
  private final LocalToolchain toolchain;
  private final Confinement confinement;
  private final ObjectMapper json;
  private final String command;

  TrivyScanService(
      WorkspacePaths paths, DependencyResolver resolver, LocalToolchain toolchain,
      Confinement confinement, ObjectMapper json,
      @Value("${petri.trivy.command:trivy}") String command) {
    this.paths = paths;
    this.resolver = resolver;
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
    return scan(worktree, subPath, scanners, severities, ignoreUnfixed, true);
  }

  public Result scan(
      Path worktree, String subPath, List<String> scanners, List<String> severities,
      boolean ignoreUnfixed, boolean resolveDependencies) throws IOException {
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
      // Maven artifacts land in the shared cache below and are read from it as the local
      // repository; otherwise Trivy fetches every pom of the tree from Maven Central itself and is
      // rate limited (HTTP 429).
      Path repository = cache.resolve("maven");
      Files.createDirectories(repository);
      Files.createDirectories(home.resolve(".m2"));
      Files.createSymbolicLink(home.resolve(".m2").resolve("repository"), repository);
      // The scan runs on a throw-away copy of the project, in which each package manager's own
      // tool produces what is missing (a resolved dependency set or a lockfile). The repository
      // itself is never written to.
      Path scanRoot = target;
      List<Resolution> steps = List.of();
      if (resolveDependencies) {
        Path copy = Files.createDirectory(home.resolve("project"));
        if (resolver.copy(target, copy)) {
          scanRoot = copy;
          steps = resolver.resolve(copy, cache, home);
        } else {
          steps = List.of(new Resolution("project", ".", DependencyResolver.SKIPPED,
              "the project is too large to copy, so dependencies were not resolved and only "
                  + "committed lockfiles were read"));
        }
      }
      List<String> argv = new ArrayList<>(List.of(
          trivy.toString(), "fs", "--quiet", "--no-progress", "--skip-version-check",
          "--format", "json", "--output", report.toString(),
          "--scanners", String.join(",", kinds),
          "--severity", String.join(",", levels),
          "--cache-dir", cache.toString(),
          "--timeout", "10m"));
      if (ignoreUnfixed) argv.add("--ignore-unfixed");
      argv.add(scanRoot.toString());
      Map<String, String> extra = new LinkedHashMap<>();
      extra.put("TRIVY_DISABLE_TELEMETRY", "true");
      extra.put("TRIVY_CACHE_DIR", cache.toString());
      List<String> wrapped = confinement.available()
          ? confinement.wrap(List.of(scanRoot, trivy.getParent()), List.of(cache, home), argv)
          : argv;
      TestCommandResult run = BoundedProcess.run(wrapped, scanRoot,
          toolchain.environment(home, extra), TIMEOUT, OUTPUT_LIMIT, "trivy");
      String note = null;
      if (!run.timedOut() && run.exitCode() != 0 && !argv.contains("--offline-scan")
          && remoteResolutionFailed(run.output())) {
        // Maven Central rate-limits (HTTP 429) the transitive resolution of pom.xml files. Scan
        // again without it: direct dependencies are still checked, indirect ones may be missing.
        List<String> offline = new ArrayList<>(argv);
        offline.add(offline.size() - 1, "--offline-scan");
        run = BoundedProcess.run(
            confinement.available()
                ? confinement.wrap(List.of(scanRoot, trivy.getParent()), List.of(cache, home), offline)
                : offline,
            scanRoot, toolchain.environment(home, extra), TIMEOUT, OUTPUT_LIMIT, "trivy");
        note = "The remote Maven repository refused the lookup of transitive dependencies, so Java "
            + "results list only dependencies that are declared or already cached. Run again later "
            + "for the full tree.";
      }
      if (run.timedOut()) {
        throw new IllegalStateException("The scan did not finish within " + TIMEOUT.toMinutes()
            + " minutes. Scan a subdirectory with the path argument instead.");
      }
      if (run.exitCode() != 0 || !Files.isRegularFile(report)) {
        throw new IllegalStateException("Trivy failed (exit " + run.exitCode() + "): "
            + tail(run.output(), 1_200));
      }
      Result parsed = parse(Files.readString(report), levels);
      List<String> gaps = new ArrayList<>();
      for (Resolution step : steps) {
        if (DependencyResolver.FAILED.equals(step.status())
            || DependencyResolver.TOOL_MISSING.equals(step.status())) {
          gaps.add(step.ecosystem() + " in " + step.directory() + ": " + step.note());
        }
      }
      String combined = String.join(" ", java.util.stream.Stream.of(
              note, gaps.isEmpty() ? null : "Not every dependency was resolved (" + String.join("; ", gaps)
                  + "), so the list may miss vulnerable dependencies.", parsed.note())
          .filter(java.util.Objects::nonNull).toList());
      boolean complete = note == null && gaps.isEmpty();
      return new Result(parsed.version(), complete, parsed.bySeverity(), parsed.byType(),
          parsed.total(), parsed.fixable(), parsed.findings(), parsed.truncated(),
          combined.isBlank() ? null : combined, steps);
    } finally {
      deleteQuietly(home);
    }
  }

  private static boolean remoteResolutionFailed(String output) {
    if (output == null) return false;
    String text = output.toLowerCase(Locale.ROOT);
    return text.contains("remote maven repository") || text.contains("429 too many requests")
        || text.contains("maven") && text.contains("failed to fetch");
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
        true, bySeverity, byType, total, fixable, shown, truncated, note, null);
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
