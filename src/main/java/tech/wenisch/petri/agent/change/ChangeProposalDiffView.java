package tech.wenisch.petri.agent.change;

import java.util.UUID;

public record ChangeProposalDiffView(
    UUID proposalId,
    String path,
    ChangeProposalStatus status,
    boolean truncated,
    String beforeContent,
    String afterContent,
    String unifiedDiff) {}
