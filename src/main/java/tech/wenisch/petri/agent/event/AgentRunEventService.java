package tech.wenisch.petri.agent.event;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.audit.AuditLogService;

@Service
public class AgentRunEventService {
  private final AgentRunEventRepository events;
  private final AuditLogService auditLog;
  private final ApplicationEventPublisher publisher;
  private final Clock clock;

  @Autowired
  public AgentRunEventService(
      AgentRunEventRepository events,
      AuditLogService auditLog,
      ApplicationEventPublisher publisher) {
    this(events, auditLog, publisher, Clock.systemUTC());
  }

  AgentRunEventService(
      AgentRunEventRepository events,
      AuditLogService auditLog,
      ApplicationEventPublisher publisher,
      Clock clock) {
    this.events = events;
    this.auditLog = auditLog;
    this.publisher = publisher;
    this.clock = clock;
  }

  @Transactional
  public AgentRunEventView record(
      UUID worktreeId,
      AgentRunEventType type,
      String toolName,
      String actor,
      String summary) {
    String safeToolName = required(toolName, "Tool name", 80);
    String safeActor = required(actor, "Actor", 320);
    String safeSummary = required(summary, "Summary", 1000);
    AgentRunEvent event = events.save(new AgentRunEvent(
        UUID.randomUUID(), worktreeId, type, safeToolName, safeSummary, Instant.now(clock)));
    AgentRunEventView view = view(event);
    auditLog.record(safeActor, type.name(), "PROJECT_WORKTREE", worktreeId, safeSummary);
    publisher.publishEvent(view);
    return view;
  }

  @Transactional(readOnly = true)
  public List<AgentRunEventView> recent(UUID worktreeId) {
    List<AgentRunEventView> result = new ArrayList<>(events
        .findTop200ByWorktreeIdOrderByOccurredAtDesc(worktreeId)
        .stream()
        .map(AgentRunEventService::view)
        .toList());
    Collections.reverse(result);
    return List.copyOf(result);
  }

  private static String required(String value, String label, int maxLength) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required");
    String stripped = value.strip();
    if (stripped.length() > maxLength) throw new IllegalArgumentException(label + " is too long");
    return stripped;
  }

  private static AgentRunEventView view(AgentRunEvent event) {
    return new AgentRunEventView(
        event.id(), event.worktreeId(), event.eventType(), event.toolName(), event.summary(),
        event.occurredAt());
  }
}
