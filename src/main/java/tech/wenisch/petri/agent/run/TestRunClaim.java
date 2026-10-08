package tech.wenisch.petri.agent.run;

import java.util.UUID;

record TestRunClaim(
    UUID id,
    UUID worktreeId,
    TestBuildSystem buildSystem,
    String commandDisplay,
    String approverEmail) {}
