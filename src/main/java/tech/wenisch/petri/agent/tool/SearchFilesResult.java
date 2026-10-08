package tech.wenisch.petri.agent.tool;

import java.util.List;

public record SearchFilesResult(List<SearchMatch> matches, boolean truncated) {}
