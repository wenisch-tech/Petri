package tech.wenisch.petri.workspace;

import org.springframework.stereotype.Component;
import tech.wenisch.petri.agent.run.LocalWorkspaceProcesses;
import tech.wenisch.petri.agent.tool.WorkspaceFileService;
import tech.wenisch.petri.git.GitCommandRunner;

/** The workspace host that is Petri's own container and data volume. */
@Component
public class LocalWorkspaceHost implements WorkspaceHost {
  private final WorkspacePaths layout;
  private final WorkspaceFileService files;
  private final LocalWorkspaceProcesses processes;
  private final GitCommandRunner git;
  private final LocalWorkspaceArchives archives;

  public LocalWorkspaceHost(
      WorkspacePaths layout, WorkspaceFileService files, LocalWorkspaceProcesses processes,
      GitCommandRunner git, LocalWorkspaceArchives archives) {
    this.layout = layout;
    this.files = files;
    this.processes = processes;
    this.git = git;
    this.archives = archives;
  }

  @Override public WorkspaceHostKind kind() { return WorkspaceHostKind.LOCAL; }
  @Override public WorkspaceLayout layout() { return layout; }
  @Override public WorkspaceFiles files() { return files; }
  @Override public WorkspaceProcesses processes() { return processes; }
  @Override public WorkspaceGit git() { return git; }
  @Override public WorkspaceArchives archives() { return archives; }
}
