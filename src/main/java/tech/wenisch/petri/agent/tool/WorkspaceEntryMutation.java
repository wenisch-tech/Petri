package tech.wenisch.petri.agent.tool;

/** A safe, user-initiated filesystem mutation within one active worktree. */
public record WorkspaceEntryMutation(String path, String type, String sha256, long size) {}
