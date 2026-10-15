package tech.wenisch.petri.agent.run;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tech.wenisch.petri.vulnerability.TrivySettingsService;
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
  private final TrivySettingsService settings;
  private final DependencyResolver resolver;
  private final LocalToolchain toolchain;
  private final Confinement confinement;
  private final ObjectMapper json;
  private final String command;

  TrivyScanService(
      WorkspacePaths paths, TrivySettingsService settings, DependencyResolver resolver,
      LocalToolchain toolchain,
      Confinement confinement, ObjectMapper json,
      @Value("${petri.trivy.command:trivy}") String command) {
    this.paths = paths;
    this.settings = settings;
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
      // Where the database comes from is an instance setting (a registry of the administrator's
      // own, or a database that ships with the image for air-gapped use).
      argv.addAll(argv.size() - 1, databaseFlags(settings.current(), cache, extra));
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

  // --- the vulnerability database ---------------------------------------------------------------

  /** What is known about one copy of the database. */
  public record DatabaseInfo(
      boolean present, String location, Instant updatedAt, Instant nextUpdate, Instant downloadedAt,
      long sizeBytes) {}

  /** The database Trivy has downloaded (or been seeded with) in Petri's cache. */
  public DatabaseInfo cachedDatabase() {
    try {
      return info(paths.directory("trivy-cache").resolve("db"));
    } catch (IOException exception) {
      return new DatabaseInfo(false, "", null, null, null, 0);
    }
  }

  /** The database that was supplied with the image (or mounted) for air-gapped use. */
  public DatabaseInfo suppliedDatabase(String offlineDbPath) {
    return info(Path.of(offlineDbPath).resolve("db"));
  }

  private DatabaseInfo info(Path directory) {
    Path database = directory.resolve("trivy.db");
    if (!Files.isRegularFile(database)) {
      return new DatabaseInfo(false, directory.toString(), null, null, null, 0);
    }
    Instant updated = null;
    Instant next = null;
    Instant downloaded = null;
    try {
      JsonNode meta = json.readTree(Files.readAllBytes(directory.resolve("metadata.json")));
      updated = instant(meta, "UpdatedAt");
      next = instant(meta, "NextUpdate");
      downloaded = instant(meta, "DownloadedAt");
    } catch (IOException | RuntimeException ignored) {
      // The database is usable without its metadata file; the dates are only informational.
    }
    long size = 0;
    try {
      size = Files.size(database);
    } catch (IOException ignored) {
      // informational
    }
    return new DatabaseInfo(true, directory.toString(), updated, next, downloaded, size);
  }

  private static Instant instant(JsonNode node, String field) {
    String text = text(node, field);
    if (text == null) return null;
    try {
      return Instant.parse(text);
    } catch (RuntimeException exception) {
      return null;
    }
  }

  /**
   * The Trivy flags for the configured database source, and the environment (registry
   * credentials) it needs. In offline mode the supplied database is copied into the cache first.
   */
  private List<String> databaseFlags(
      TrivySettingsService.Config config, Path cache, Map<String, String> environment)
      throws IOException {
    List<String> flags = new ArrayList<>();
    if (config.mode() == TrivySettingsService.Mode.OFFLINE) {
      seedFromImage(config.offlineDbPath(), cache);
      flags.addAll(List.of("--skip-db-update", "--skip-java-db-update", "--skip-check-update",
          "--offline-scan"));
      return flags;
    }
    if (config.dbRepository() != null) flags.addAll(List.of("--db-repository", config.dbRepository()));
    if (config.javaDbRepository() != null) {
      flags.addAll(List.of("--java-db-repository", config.javaDbRepository()));
    }
    if (config.checksRepository() != null) {
      flags.addAll(List.of("--checks-bundle-repository", config.checksRepository()));
    }
    // Credentials travel in the environment, never on the command line.
    if (config.username() != null && config.password() != null) {
      environment.put("TRIVY_USERNAME", config.username());
      environment.put("TRIVY_PASSWORD", config.password());
    }
    if (config.insecure()) {
      environment.put("TRIVY_INSECURE", "true");
      environment.put("TRIVY_NON_SSL", "true");
    }
    return flags;
  }

  /** Copies the database supplied with the image into the cache when the cache has another one. */
  private void seedFromImage(String offlineDbPath, Path cache) throws IOException {
    Path supplied = Path.of(offlineDbPath).resolve("db");
    Path suppliedDb = supplied.resolve("trivy.db");
    if (!Files.isRegularFile(suppliedDb)) {
      throw new IllegalStateException("The scanner is set to offline mode, but there is no "
          + "database at " + supplied + ". Build the image with BAKE_TRIVY_DB=true or place the "
          + "Trivy database (db/trivy.db and db/metadata.json) there.");
    }
    Path target = cache.resolve("db");
    Path metaSupplied = supplied.resolve("metadata.json");
    Path metaTarget = target.resolve("metadata.json");
    boolean current = Files.isRegularFile(target.resolve("trivy.db"))
        && Files.isRegularFile(metaSupplied) && Files.isRegularFile(metaTarget)
        && Files.readString(metaSupplied).equals(Files.readString(metaTarget))
        && Files.size(suppliedDb) == Files.size(target.resolve("trivy.db"));
    if (current) return;
    Files.createDirectories(target);
    for (String name : List.of("trivy.db", "metadata.json")) {
      Path from = supplied.resolve(name);
      if (!Files.isRegularFile(from)) continue;
      Path temporary = target.resolve(name + ".seed");
      Files.copy(from, temporary, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      Files.move(temporary, target.resolve(name), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
          java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }
  }

  /**
   * Makes the database ready the way a scan would and says what it found: in offline mode the
   * supplied database is installed, otherwise it is downloaded from the configured registry (with
   * its credentials), which also proves that the registry, path and credentials work.
   */
  public String checkDatabase() throws IOException {
    TrivySettingsService.Config config = settings.current();
    Path cache = paths.directory("trivy-cache");
    if (config.mode() == TrivySettingsService.Mode.OFFLINE) {
      seedFromImage(config.offlineDbPath(), cache);
      DatabaseInfo ready = cachedDatabase();
      return "Offline database installed from " + config.offlineDbPath()
          + (ready.updatedAt() == null ? "" : " (built " + ready.updatedAt() + ")") + ".";
    }
    Path trivy = executable();
    if (trivy == null) throw new IllegalStateException("Trivy is not installed in this Petri instance.");
    Path home = Files.createTempDirectory(paths.temporaryDirectory(), "petri-trivy-");
    try {
      List<String> argv = new ArrayList<>(List.of(trivy.toString(), "image", "--download-db-only",
          "--quiet", "--no-progress", "--skip-version-check", "--cache-dir", cache.toString()));
      Map<String, String> extra = new LinkedHashMap<>();
      extra.put("TRIVY_DISABLE_TELEMETRY", "true");
      List<String> flags = databaseFlags(config, cache, extra);
      argv.addAll(flags);
      List<String> wrapped = confinement.available()
          ? confinement.wrap(List.of(trivy.getParent()), List.of(cache, home), argv) : argv;
      TestCommandResult run = BoundedProcess.run(wrapped, home, toolchain.environment(home, extra),
          Duration.ofMinutes(5), 16 * 1024, "trivy-db");
      if (run.timedOut()) throw new IllegalStateException("The download did not finish within 5 minutes.");
      if (run.exitCode() != 0) {
        throw new IllegalStateException("Trivy could not get the database: "
            + scrub(tail(run.output(), 800), config));
      }
      DatabaseInfo ready = cachedDatabase();
      return "Database ready" + (ready.updatedAt() == null ? "" : ", built " + ready.updatedAt())
          + (config.dbRepository() == null ? " (default registry)" : " from " + config.dbRepository())
          + ".";
    } finally {
      deleteQuietly(home);
    }
  }

  /** Trivy's error text never needs the registry password; make sure it cannot show up. */
  private static String scrub(String text, TrivySettingsService.Config config) {
    String result = text;
    if (config.password() != null && !config.password().isBlank()) {
      result = result.replace(config.password(), "********");
    }
    return result;
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
