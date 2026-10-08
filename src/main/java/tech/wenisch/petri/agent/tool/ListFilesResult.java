package tech.wenisch.petri.agent.tool;

import java.util.List;

public record ListFilesResult(List<FileEntry> entries, boolean truncated) {}
