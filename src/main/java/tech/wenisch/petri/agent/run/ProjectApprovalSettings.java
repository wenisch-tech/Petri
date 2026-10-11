package tech.wenisch.petri.agent.run;

public record ProjectApprovalSettings(
    boolean autoApprovePackageInstalls, boolean autoApproveTerminalCommands) {}
