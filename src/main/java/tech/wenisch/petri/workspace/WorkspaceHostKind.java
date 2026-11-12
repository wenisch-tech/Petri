package tech.wenisch.petri.workspace;

/** Where a project's workspace (mirror, worktrees and the processes working on them) lives. */
public enum WorkspaceHostKind {
  LOCAL,
  KUBERNETES
}
