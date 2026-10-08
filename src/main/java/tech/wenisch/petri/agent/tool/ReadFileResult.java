package tech.wenisch.petri.agent.tool;

public record ReadFileResult(String path, String content, String sha256, long size) {}
