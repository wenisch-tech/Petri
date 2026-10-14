package tech.wenisch.petri.git.provider;

/** Maps each provider's run states to one small vocabulary. */
final class PipelineStatus {
  static final String SUCCESS = "SUCCESS";
  static final String FAILED = "FAILED";
  static final String RUNNING = "RUNNING";
  static final String PENDING = "PENDING";
  static final String CANCELLED = "CANCELLED";
  static final String SKIPPED = "SKIPPED";
  static final String UNKNOWN = "UNKNOWN";

  private PipelineStatus() {}

  /** GitHub Actions and Forgejo Actions: a status plus, once finished, a conclusion. */
  static String github(String status, String conclusion) {
    String value = (conclusion != null ? conclusion : status == null ? "" : status).toLowerCase();
    return switch (value) {
      case "success", "completed_success" -> SUCCESS;
      case "failure", "failed", "timed_out", "startup_failure", "error", "action_required" -> FAILED;
      case "cancelled", "canceled" -> CANCELLED;
      case "skipped", "neutral", "stale" -> SKIPPED;
      case "in_progress", "running" -> RUNNING;
      case "queued", "waiting", "pending", "requested", "blocked" -> PENDING;
      case "completed" -> UNKNOWN;
      default -> UNKNOWN;
    };
  }

  static String gitlab(String status) {
    return switch (status == null ? "" : status) {
      case "success" -> SUCCESS;
      case "failed" -> FAILED;
      case "running" -> RUNNING;
      case "created", "waiting_for_resource", "preparing", "pending", "scheduled", "manual" -> PENDING;
      case "canceled", "canceling" -> CANCELLED;
      case "skipped" -> SKIPPED;
      default -> UNKNOWN;
    };
  }
}
