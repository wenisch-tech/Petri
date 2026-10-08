package tech.wenisch.petri.git;

public record GitCommandResult(int exitCode, String output, boolean timedOut, boolean truncated) {
  public boolean succeeded() {
    return !timedOut && exitCode == 0;
  }
}
