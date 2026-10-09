package tech.wenisch.petri.agent.orchestration;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class AgentChatStream {
  private static final long TIMEOUT_MILLIS = 30L * 60L * 1000L;
  private final Map<UUID, CopyOnWriteArrayList<SseEmitter>> subscribers = new ConcurrentHashMap<>();

  public synchronized SseEmitter subscribe(UUID chatId, AgentChatView snapshot) {
    SseEmitter emitter = new SseEmitter(TIMEOUT_MILLIS);
    subscribers.computeIfAbsent(chatId, ignored -> new CopyOnWriteArrayList<>()).add(emitter);
    emitter.onCompletion(() -> remove(chatId, emitter));
    emitter.onTimeout(() -> remove(chatId, emitter));
    emitter.onError(ignored -> remove(chatId, emitter));
    send(emitter, "snapshot", snapshot);
    return emitter;
  }

  public void textDelta(UUID chatId, UUID runId, String delta) {
    publish(chatId, "assistant-delta", Map.of("runId", runId, "delta", delta));
  }

  public void runStatus(UUID chatId, AgentRunView run) {
    publish(chatId, "run-status", Map.of(
        "runId", run.id(), "status", run.status().name(), "partialOutput", run.partialOutput() == null
            ? "" : run.partialOutput()));
  }

  public void toolLifecycle(UUID chatId, UUID runId, String toolName, String status) {
    publish(chatId, "tool-lifecycle", Map.of(
        "runId", runId, "toolName", toolName, "status", status));
  }

  public void summaryStatus(UUID chatId, String status) {
    publish(chatId, "summary-status", Map.of("status", status));
  }

  private void publish(UUID chatId, String name, Object data) {
    for (SseEmitter emitter : subscribers.getOrDefault(chatId, new CopyOnWriteArrayList<>())) {
      send(emitter, name, data);
    }
  }

  private void send(SseEmitter emitter, String name, Object data) {
    try {
      emitter.send(SseEmitter.event().name(name).data(data));
    } catch (IOException | IllegalStateException exception) {
      emitter.completeWithError(exception);
    }
  }

  private synchronized void remove(UUID chatId, SseEmitter emitter) {
    CopyOnWriteArrayList<SseEmitter> listeners = subscribers.get(chatId);
    if (listeners == null) return;
    listeners.remove(emitter);
    if (listeners.isEmpty()) subscribers.remove(chatId, listeners);
  }
}
