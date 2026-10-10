package tech.wenisch.petri.git;

import java.util.List;
import java.util.UUID;

public record WorkspaceGitStatusView(
    UUID worktreeId,
    String branchName,
    String upstreamBranch,
    String headCommit,
    boolean detached,
    int ahead,
    int behind,
    boolean clean,
    WorkspaceGitOperationState operation,
    List<String> conflicts,
    List<WorkspaceChangeView> staged,
    List<WorkspaceChangeView> unstaged,
    List<WorkspaceChangeView> untracked) {}
