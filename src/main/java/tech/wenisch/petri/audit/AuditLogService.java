package tech.wenisch.petri.audit;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AuditLogService {
  private final AuditEventRepository events;
  private final Clock clock;

  @Autowired
  public AuditLogService(AuditEventRepository events) {
    this(events, Clock.systemUTC());
  }

  AuditLogService(AuditEventRepository events, Clock clock) {
    this.events = events;
    this.clock = clock;
  }

  public void record(
      String actorEmail, String action, String targetType, UUID targetId, String summary) {
    events.save(new AuditEvent(
        UUID.randomUUID(),
        Instant.now(clock),
        actorEmail,
        action,
        targetType,
        targetId,
        summary));
  }
}
