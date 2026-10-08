package tech.wenisch.petri.agent.event;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class AgentRunEventStream {
  private static final int MAX_SUBSCRIBERS_PER_WORKTREE = 20;
  private static final long EMITTER_TIMEOUT_MILLIS = 30L * 60L * 1000L;

  private final Map<UUID, CopyOnWriteArrayList<SseEmitter>> subscribers =
      new ConcurrentHashMap<>();

  public synchronized SseEmitter subscribe(UUID worktreeId) {
    CopyOnWriteArrayList<SseEmitter> worktreeSubscribers =
        subscribers.computeIfAbsent(worktreeId, ignored -> new CopyOnWriteArrayList<>());
    if (worktreeSubscribers.size() >= MAX_SUBSCRIBERS_PER_WORKTREE) {
      throw new IllegalStateException("Too many event stream subscribers for this worktree");
    }
    SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MILLIS);
    worktreeSubscribers.add(emitter);
    emitter.onCompletion(() -> remove(worktreeId, emitter));
    emitter.onTimeout(() -> remove(worktreeId, emitter));
    emitter.onError(ignored -> remove(worktreeId, emitter));
    try {
      emitter.send(SseEmitter.event()
          .name("connected")
          .data(Map.of("worktreeId", worktreeId)));
    } catch (IOException exception) {
      remove(worktreeId, emitter);
      throw new IllegalStateException("Could not open event stream", exception);
    }
    return emitter;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void publish(AgentRunEventView event) {
    List<SseEmitter> worktreeSubscribers = subscribers.get(event.worktreeId());
    if (worktreeSubscribers == null) return;
    for (SseEmitter emitter : worktreeSubscribers) {
      try {
        emitter.send(SseEmitter.event()
            .id(event.id().toString())
            .name("agent-run-event")
            .data(event));
      } catch (IOException | IllegalStateException exception) {
        remove(event.worktreeId(), emitter);
        emitter.completeWithError(exception);
      }
    }
  }

  private synchronized void remove(UUID worktreeId, SseEmitter emitter) {
    CopyOnWriteArrayList<SseEmitter> worktreeSubscribers = subscribers.get(worktreeId);
    if (worktreeSubscribers == null) return;
    worktreeSubscribers.remove(emitter);
    if (worktreeSubscribers.isEmpty()) subscribers.remove(worktreeId, worktreeSubscribers);
  }
}
