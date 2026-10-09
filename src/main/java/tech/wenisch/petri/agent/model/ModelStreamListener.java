package tech.wenisch.petri.agent.model;

@FunctionalInterface
public interface ModelStreamListener {
  void onTextDelta(String delta);

  default void onToolCallDelta(int index, String callId, String name, String argumentsDelta) {}

  default void onCompleted(ModelResponse response) {}

  default void onUsage(int inputTokens, int outputTokens) {}

  default void onError(String message) {}

  static ModelStreamListener tracking(ModelStreamListener delegate, Runnable observedDelta) {
    return new ModelStreamListener() {
      @Override public void onTextDelta(String delta) {
        observedDelta.run();
        delegate.onTextDelta(delta);
      }

      @Override public void onToolCallDelta(int index, String callId, String name, String argumentsDelta) {
        observedDelta.run();
        delegate.onToolCallDelta(index, callId, name, argumentsDelta);
      }

      @Override public void onCompleted(ModelResponse response) { delegate.onCompleted(response); }
      @Override public void onUsage(int inputTokens, int outputTokens) {
        delegate.onUsage(inputTokens, outputTokens);
      }
      @Override public void onError(String message) { delegate.onError(message); }
    };
  }
}
