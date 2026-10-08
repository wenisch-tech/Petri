package tech.wenisch.petri.git;

public class GitOperationException extends RuntimeException {
  public GitOperationException(String message) {
    super(message);
  }

  public GitOperationException(String message, Throwable cause) {
    super(message, cause);
  }
}
