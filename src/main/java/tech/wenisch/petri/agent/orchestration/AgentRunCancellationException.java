package tech.wenisch.petri.agent.orchestration;

final class AgentRunCancellationException extends RuntimeException {
  AgentRunCancellationException() {
    super("Agent run cancellation was requested");
  }
}

