package tech.wenisch.petri.agent.run;

import java.util.UUID;

/** A project's sandbox settings as shown on its settings page; a null network follows the profile. */
public record ProjectSandboxChoice(
    UUID profileId, Boolean networkEnabled, boolean autoApprovePackageInstalls,
    boolean autoApproveTerminalCommands) {}
