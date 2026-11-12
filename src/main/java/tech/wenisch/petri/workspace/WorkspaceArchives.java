package tech.wenisch.petri.workspace;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;

/** Moves directory trees between a host and Petri's local filesystem. */
public interface WorkspaceArchives {
  /** Copies a host tree to a local directory; false when the byte or file cap was exceeded. */
  boolean downloadTree(Path hostDirectory, Path localDestination, Set<String> skipDirectories,
      long maxBytes, int maxFiles) throws IOException;

  /** Copies a local directory into a host directory below its temporary directories. */
  void uploadTree(Path localDirectory, Path hostDestination) throws IOException;
}
