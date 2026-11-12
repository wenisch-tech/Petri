package tech.wenisch.petri.kubernetes;

import java.util.UUID;

/** Hands out the running pod of a project for the duration of one operation. */
public interface PodSessions {
  /** A use of the pod. While open, the pod is never reaped; closing counts as activity. */
  interface Session extends AutoCloseable {
    PodHandle handle();

    @Override
    void close();
  }

  Session use(UUID projectId);

  /** Drops what is remembered about the pod's health, for example after the pod vanished. */
  default void forget(UUID projectId) {}
}
