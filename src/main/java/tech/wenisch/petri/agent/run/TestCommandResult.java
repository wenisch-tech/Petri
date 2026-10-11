package tech.wenisch.petri.agent.run;

record TestCommandResult(
    int exitCode, String output, boolean timedOut, boolean truncated, String imageDigest) {}
