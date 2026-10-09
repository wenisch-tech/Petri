package tech.wenisch.petri.agent.orchestration;

import java.time.Instant;
import java.util.UUID;
import tech.wenisch.petri.agent.model.ModelProvider;

/** A persisted, internal context-compaction revision shown without hiding source turns. */
public record AgentChatSummaryView(
    UUID id,
    int fromMessageIndex,
    int throughMessageIndex,
    String content,
    ModelProvider provider,
    String model,
    Instant createdAt) {}
