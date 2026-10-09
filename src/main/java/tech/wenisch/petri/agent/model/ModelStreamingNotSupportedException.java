package tech.wenisch.petri.agent.model;

/** Signals an endpoint that explicitly cannot honour a streaming request. */
final class ModelStreamingNotSupportedException extends ModelClientException {
  ModelStreamingNotSupportedException(String message) {
    super(message);
  }
}
