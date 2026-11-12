package tech.wenisch.petri.kubernetes;

/** The workspace pod of a project could not be reached or started; the message is user-facing. */
public class WorkspaceHostUnavailableException extends IllegalStateException {
  private final boolean podGone;

  public WorkspaceHostUnavailableException(String message) {
    this(message, false);
  }

  public WorkspaceHostUnavailableException(String message, Throwable cause) {
    super(message, cause);
    this.podGone = false;
  }

  private WorkspaceHostUnavailableException(String message, boolean podGone) {
    super(message);
    this.podGone = podGone;
  }

  /**
   * The API server answered that the pod does not exist, so the request never reached the agent and
   * can safely be repeated once the pod has been started again.
   */
  public static WorkspaceHostUnavailableException podGone(String message) {
    return new WorkspaceHostUnavailableException(message, true);
  }

  public boolean isPodGone() {
    return podGone;
  }
}
