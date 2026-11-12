package tech.wenisch.petri.kubernetes;

/** Lifecycle of a project's workspace pod as Petri tracks it. */
public enum WorkspacePodPhase {
  /** No pod exists; the volume (if any) is kept. It starts on first use. */
  STOPPED,
  STARTING,
  RUNNING,
  STOPPING,
  /** The last start failed; see the recorded error. */
  FAILED
}
