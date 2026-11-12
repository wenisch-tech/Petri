package tech.wenisch.petri.agent.run;

public record TestCommandResult(
    int exitCode, String output, boolean timedOut, boolean truncated, String imageDigest) {}
