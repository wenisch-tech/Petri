package tech.wenisch.petri.git;

public record WorkspaceChangeView(
    String path,
    String previousPath,
    WorkspaceChangeStatus status,
    boolean staged,
    boolean unstaged,
    boolean binary,
    Integer additions,
    Integer deletions) {}
