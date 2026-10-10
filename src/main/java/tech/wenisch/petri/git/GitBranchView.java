package tech.wenisch.petri.git;

import java.util.UUID;

public record GitBranchView(
    String name,
    GitBranchKind kind,
    boolean defaultBranch,
    String upstream,
    Integer ahead,
    Integer behind,
    UUID worktreeId,
    boolean protectedBranch) {}
