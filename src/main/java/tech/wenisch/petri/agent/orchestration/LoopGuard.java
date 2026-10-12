package tech.wenisch.petri.agent.orchestration;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Detects a model that keeps repeating itself: the same tool calls as in its previous response, or
 * (nearly) the same text as in one of its last responses. Weak models do this when they cannot make
 * up their mind, for example re-reading the same diff and re-writing the same assessment.
 *
 * <p>The consequence is graded. After the first repetition the next request carries an instruction
 * to read its own last message and not repeat it. After the second consecutive repetition tools are
 * withdrawn and the model must answer with what it already has.
 */
final class LoopGuard {
  /** Consecutive repetitions after which tools are withdrawn. */
  static final int WITHDRAW_TOOLS_AT = 2;

  private static final int MIN_TEXT_LENGTH = 200;
  private static final int TEXT_WINDOW = 3;
  private static final double SIMILARITY = 0.8;

  private LoopGuard() {}

  /** One model response with the tool calls it requested. */
  record Response(String text, List<String> calls) {}

  /** Number of consecutive repetitive responses at the end of the run (0 = not looping). */
  static int strikes(List<AgentTurn> history) {
    List<Response> responses = responses(history);
    int strikes = 0;
    for (int index = responses.size() - 1; index > 0; index--) {
      if (!repeats(responses, index)) break;
      strikes++;
    }
    return strikes;
  }

  static boolean repeats(List<Response> responses, int index) {
    Response current = responses.get(index);
    Response previous = responses.get(index - 1);
    if (!current.calls().isEmpty() && sameCalls(current.calls(), previous.calls())) return true;
    String text = normalize(current.text());
    if (text.length() < MIN_TEXT_LENGTH) return false;
    for (int back = 1; back <= TEXT_WINDOW && index - back >= 0; back++) {
      String earlier = normalize(responses.get(index - back).text());
      if (earlier.length() >= MIN_TEXT_LENGTH && similarity(text, earlier) >= SIMILARITY) return true;
    }
    return false;
  }

  static List<Response> responses(List<AgentTurn> history) {
    List<Response> result = new ArrayList<>();
    String responseId = null;
    String text = null;
    List<String> calls = new ArrayList<>();
    for (AgentTurn turn : history) {
      if (turn.kind() == AgentTurnKind.MODEL_RESPONSE) {
        if (responseId != null) result.add(new Response(text, List.copyOf(calls)));
        responseId = turn.providerResponseId();
        text = turn.content();
        calls = new ArrayList<>();
      } else if (turn.kind() == AgentTurnKind.TOOL_CALL && responseId != null
          && responseId.equals(turn.providerResponseId())) {
        calls.add(turn.toolName() + "\u0000" + normalizeArguments(turn.content()));
      }
    }
    if (responseId != null) result.add(new Response(text, List.copyOf(calls)));
    return result;
  }

  private static boolean sameCalls(List<String> left, List<String> right) {
    return !left.isEmpty() && new HashSet<>(left).equals(new HashSet<>(right));
  }

  private static String normalizeArguments(String json) {
    return json == null ? "" : json.replaceAll("\\s+", "");
  }

  private static String normalize(String text) {
    return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
  }

  /** Jaccard similarity of the word sets. */
  static double similarity(String left, String right) {
    Set<String> a = words(left);
    Set<String> b = words(right);
    if (a.isEmpty() || b.isEmpty()) return 0;
    Set<String> intersection = new HashSet<>(a);
    intersection.retainAll(b);
    Set<String> union = new HashSet<>(a);
    union.addAll(b);
    return (double) intersection.size() / union.size();
  }

  private static Set<String> words(String text) {
    Set<String> result = new HashSet<>();
    for (String word : text.split("[^\\p{L}\\p{N}_]+")) if (!word.isEmpty()) result.add(word);
    return result;
  }

  /** Appended to the instructions of the next request after a repetition. */
  static String guidance(int strikes) {
    if (strikes <= 0) return "";
    if (strikes < WITHDRAW_TOOLS_AT) {
      return "\nLOOP GUARD: your previous response repeated an earlier one (the same tool calls or "
          + "almost the same text). Read your last message and decide from it how to continue. Do "
          + "not repeat it. If it already contains everything you need, give your final response "
          + "now in the required format; otherwise take a different next step.\n";
    }
    return "\nLOOP GUARD: you have repeated yourself several times in a row. Tools are no longer "
        + "available. Using what you have already gathered, give your final response now in the "
        + "required format. State briefly what could not be verified instead of repeating the "
        + "analysis.\n";
  }

  /** Result returned for tool calls the model makes after tools were withdrawn. */
  static String skippedToolMessage() {
    return "Skipped by the loop guard: you are repeating yourself and tools are withdrawn. "
        + "Give your final response now.";
  }
}
