package tech.wenisch.petri.workspace;

import java.nio.file.Path;
import java.util.UUID;

/** Builders for local-style workspace hosts and locators used by unit tests. */
public final class TestWorkspaceHosts {
  private TestWorkspaceHosts() {}

  /** A local host made of the given parts; parts a test does not use may be null. */
  public static WorkspaceHost host(
      WorkspaceLayout layout, WorkspaceFiles files, WorkspaceProcesses processes, WorkspaceGit git,
      WorkspaceArchives archives) {
    return new WorkspaceHost() {
      @Override public WorkspaceHostKind kind() { return WorkspaceHostKind.LOCAL; }
      @Override public WorkspaceLayout layout() { return layout; }
      @Override public WorkspaceFiles files() { return files; }
      @Override public WorkspaceProcesses processes() { return processes; }
      @Override public WorkspaceGit git() { return git; }
      @Override public WorkspaceArchives archives() { return archives; }
    };
  }

  public static WorkspaceHost filesOnly(WorkspaceFiles files) {
    return host(null, files, null, null, null);
  }

  public static WorkspaceResolver resolver(WorkspaceHost host) {
    return new WorkspaceResolver(host);
  }

  /** Locator that always returns the same worktree on the same host. */
  public static WorktreeLocator locator(Path worktree, WorkspaceHost host) {
    return new WorktreeLocator() {
      @Override public Path requireActiveWorktree(UUID worktreeId) { return worktree; }

      @Override
      public ActiveWorktree resolveActiveWorktree(UUID worktreeId) {
        return new ActiveWorktree(worktreeId, UUID.randomUUID(), worktree, host);
      }
    };
  }

  /** Resolver that always answers with one host. */
  public static final class WorkspaceResolver implements WorkspaceHostResolver {
    private final WorkspaceHost host;

    WorkspaceResolver(WorkspaceHost host) {
      this.host = host;
    }

    @Override public WorkspaceHost forProject(UUID projectId) { return host; }
    @Override public WorkspaceHost forWorktree(UUID worktreeId) { return host; }
  }
}
