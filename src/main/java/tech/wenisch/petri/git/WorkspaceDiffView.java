package tech.wenisch.petri.git;

public record WorkspaceDiffView(
    String path,
    String previousPath,
    WorkspaceChangeStatus status,
    boolean binary,
    boolean truncated,
    String beforeContent,
    String afterContent,
    String unifiedDiff) {}
