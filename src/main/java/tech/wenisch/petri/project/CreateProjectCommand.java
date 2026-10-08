package tech.wenisch.petri.project;

public record CreateProjectCommand(String name, String repositoryUri, String defaultBranch) {}
