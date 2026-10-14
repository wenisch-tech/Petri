package tech.wenisch.petri.agent.tool;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tech.wenisch.petri.agent.run.TrivyScanService;
import tech.wenisch.petri.workspace.WorktreeLocator;

/**
 * Runs Trivy over the project's worktree and returns counts and the most important findings. It is
 * read-only: it never changes files and the scan runs confined to the worktree.
 */
@Component
public class ScanVulnerabilitiesTool implements AgentTool {
  private static final ToolDefinition DEFINITION = new ToolDefinition(
      "scan_vulnerabilities",
      "Scan the project with Trivy for known vulnerabilities in dependencies, committed secrets "
          + "and infrastructure misconfigurations. Returns counts by severity and the most "
          + "important findings with fixed versions. Read-only. The first scan downloads the "
          + "vulnerability database and can take a few minutes.",
      ToolSchemas.object(
          Map.of(
              "path", ToolSchemas.nullableString(
                  "Subdirectory to scan, relative to the project root, or null for the whole project."),
              "scanners", Map.of("type", "array",
                  "items", Map.of("type", "string", "enum", List.of("vuln", "secret", "misconfig")),
                  "description", "What to look for. Use [\"vuln\",\"secret\"] unless asked otherwise."),
              "severities", Map.of("type", "array",
                  "items", Map.of("type", "string",
                      "enum", List.of("CRITICAL", "HIGH", "MEDIUM", "LOW", "UNKNOWN")),
                  "description", "Severities to report. Use [\"CRITICAL\",\"HIGH\",\"MEDIUM\"] unless asked otherwise."),
              "ignoreUnfixed", Map.of("type", "boolean",
                  "description", "Hide vulnerabilities that have no fixed version yet.")),
          List.of("path", "scanners", "severities", "ignoreUnfixed")));

  private final WorktreeLocator worktrees;
  private final TrivyScanService trivy;

  public ScanVulnerabilitiesTool(WorktreeLocator worktrees, TrivyScanService trivy) {
    this.worktrees = worktrees;
    this.trivy = trivy;
  }

  @Override
  public ToolDefinition definition() { return DEFINITION; }

  @Override
  public Object execute(ToolExecutionContext context, Map<String, Object> input) {
    Path worktree = worktrees.requireActiveWorktree(context.worktreeId());
    TrivyScanService.Result result;
    try {
      result = trivy.scan(worktree, ToolInputs.string(input, "path", null, 500),
          strings(input.get("scanners")), strings(input.get("severities")),
          Boolean.TRUE.equals(input.get("ignoreUnfixed")), true);
    } catch (IOException exception) {
      throw new IllegalStateException("The scan could not be run: " + exception.getMessage());
    }
    Map<String, Object> output = new LinkedHashMap<>();
    output.put("trivyVersion", result.version());
    output.put("totalFindings", result.total());
    output.put("fixable", result.fixable());
    output.put("bySeverity", result.bySeverity());
    output.put("byType", result.byType());
    output.put("complete", result.complete());
    if (result.dependencyResolution() != null) {
      List<Map<String, Object>> steps = new ArrayList<>();
      for (TrivyScanService.Resolution step : result.dependencyResolution()) {
        steps.add(Map.of("ecosystem", step.ecosystem(), "directory", step.directory(),
            "status", step.status(), "note", step.note() == null ? "" : step.note()));
      }
      output.put("dependencyResolution", steps);
    }
    output.put("findingsShown", result.findings().size());
    output.put("findingsTruncated", result.truncated());
    if (result.note() != null) output.put("note", result.note());
    List<Map<String, Object>> findings = new ArrayList<>();
    for (TrivyScanService.Finding finding : result.findings()) {
      Map<String, Object> entry = new LinkedHashMap<>();
      entry.put("type", finding.type());
      entry.put("severity", finding.severity());
      entry.put("id", finding.id());
      if (finding.title() != null) entry.put("title", finding.title());
      entry.put("file", finding.target());
      if (finding.line() != null) entry.put("line", finding.line());
      if (finding.pkg() != null) entry.put("package", finding.pkg());
      if (finding.installed() != null) entry.put("installed", finding.installed());
      if (finding.fixed() != null) entry.put("fixedIn", finding.fixed());
      if (finding.url() != null) entry.put("reference", finding.url());
      findings.add(entry);
    }
    output.put("findings", findings);
    return output;
  }

  private static List<String> strings(Object raw) {
    if (!(raw instanceof List<?> list)) return List.of();
    List<String> values = new ArrayList<>();
    for (Object item : list) {
      if (!(item instanceof String value)) continue;
      String trimmed = value.strip();
      boolean scanner = List.of("vuln", "secret", "misconfig").contains(trimmed.toLowerCase());
      values.add(scanner ? trimmed.toLowerCase() : trimmed.toUpperCase());
    }
    return values;
  }
}
