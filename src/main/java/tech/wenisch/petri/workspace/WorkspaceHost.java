package tech.wenisch.petri.workspace;

/**
 * One place where a project's workspace lives. Local hosts use Petri's own container; remote hosts
 * (Kubernetes pods) implement the same operations over an agent. Paths exchanged with a host are host
 * paths and POSIX-style: workspace features already require Linux.
 */
public interface WorkspaceHost {
  WorkspaceHostKind kind();

  WorkspaceLayout layout();

  WorkspaceFiles files();

  WorkspaceProcesses processes();

  WorkspaceGit git();

  WorkspaceArchives archives();

  /** The project a remote host belongs to; a local host serves every project and returns null. */
  default java.util.UUID projectId() {
    return null;
  }
}
