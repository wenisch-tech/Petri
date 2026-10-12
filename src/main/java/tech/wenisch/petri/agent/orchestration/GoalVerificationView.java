package tech.wenisch.petri.agent.orchestration;

import java.util.List;

/** One acceptance criterion of the current plan and what is known about its verification. */
public record GoalVerificationView(
    String id,
    String text,
    GoalStatus status,
    String note,
    List<String> reasons,
    List<Evidence> evidence) {

  /**
   * A piece of evidence the reviewer cited.
   *
   * @param type TEST or FILE
   * @param ref the test run id or the file path as cited
   * @param label what to show, such as the test command
   * @param state PASSED, FAILED, APPLIED or UNRESOLVED
   */
  public record Evidence(String type, String ref, String label, String state) {}
}
