package tech.wenisch.petri.agent.tool;

import java.util.Set;

/**
 * Tools available to unattended automation runs: read-only repository access plus the structured
 * review submission. There are deliberately no write, command, test or install tools.
 */
public final class AutomationToolPolicy {
  public static final String SUBMIT_REVIEW = "submit_review";

  private static final Set<String> ALLOWED = Set.of(
      "list_files", "read_file", "search_files", "get_pull_request", "get_pipeline_log",
      "scan_vulnerabilities", "submit_vulnerability_report", SUBMIT_REVIEW);

  private static final java.util.Map<String, Set<String>> CAPABILITIES = java.util.Map.of(
      "read_repo", Set.of("list_files", "read_file", "search_files", "get_pull_request"),
      "scan", Set.of("scan_vulnerabilities"),
      "pipeline_logs", Set.of("get_pipeline_log"));

  private AutomationToolPolicy() {}

  /**
   * The tools one automation run gets: what its skill asks for ({@code requires}), never more than
   * {@link #allowed()}, plus the result tool of the kind of run. A skill that asks for nothing
   * still reads the repository; capabilities this policy does not know grant nothing.
   */
  public static Set<String> forCapabilities(Set<String> requires, boolean vulnerability) {
    java.util.Set<String> tools = new java.util.LinkedHashSet<>();
    Set<String> asked = requires == null || requires.isEmpty() ? Set.of("read_repo") : requires;
    for (String capability : asked) tools.addAll(CAPABILITIES.getOrDefault(capability, Set.of()));
    tools.add(vulnerability ? "submit_vulnerability_report" : SUBMIT_REVIEW);
    tools.retainAll(ALLOWED);
    return Set.copyOf(tools);
  }

  public static Set<String> allowed() {
    return ALLOWED;
  }

  /** Tools that make no sense outside an automation run and are refused elsewhere. */
  public static boolean isAutomationOnly(String toolName) {
    return SUBMIT_REVIEW.equals(toolName);
  }
}
