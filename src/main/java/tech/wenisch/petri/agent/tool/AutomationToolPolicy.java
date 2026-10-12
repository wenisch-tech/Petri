package tech.wenisch.petri.agent.tool;

import java.util.Set;

/**
 * Tools available to unattended automation runs: read-only repository access plus the structured
 * review submission. There are deliberately no write, command, test or install tools.
 */
public final class AutomationToolPolicy {
  public static final String SUBMIT_REVIEW = "submit_review";

  private static final Set<String> ALLOWED = Set.of(
      "list_files", "read_file", "search_files", SUBMIT_REVIEW);

  private AutomationToolPolicy() {}

  public static Set<String> allowed() {
    return ALLOWED;
  }

  /** Tools that make no sense outside an automation run and are refused elsewhere. */
  public static boolean isAutomationOnly(String toolName) {
    return SUBMIT_REVIEW.equals(toolName);
  }
}
