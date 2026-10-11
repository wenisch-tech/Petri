package tech.wenisch.petri.git.provider;

public class GitProviderException extends RuntimeException {
  public GitProviderException(String message) { super(message); }
  public GitProviderException(String message, Throwable cause) { super(message, cause); }
}
