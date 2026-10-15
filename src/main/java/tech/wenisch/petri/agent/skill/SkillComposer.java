package tech.wenisch.petri.agent.skill;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Builds the text an agent follows for a skill that builds on other skills: each skill named by
 * {@code extends}, base first, then the skill itself, without their front matter.
 */
public final class SkillComposer {
  private static final int MAX_DEPTH = 4;
  private static final String SEPARATOR = "\n\n---\n\n";

  private SkillComposer() {}

  /**
   * @param fallbackBase used when the skill has no {@code extends} of its own, so skills that
   *     predate the manifest still compose with the base they were registered with; may be null
   */
  public static String compose(SkillService skills, String name, String fallbackBase) {
    List<SkillDocument> chain = chain(skills, name, fallbackBase);
    StringBuilder text = new StringBuilder();
    for (SkillDocument document : chain) {
      if (!text.isEmpty()) text.append(SEPARATOR);
      text.append(body(document.content()));
    }
    return text.toString();
  }

  /** The capabilities a skill and everything it extends ask for ({@code requires}), in order. */
  public static Set<String> requires(SkillService skills, String name, String fallbackBase) {
    Set<String> all = new LinkedHashSet<>();
    for (SkillDocument document : chain(skills, name, fallbackBase)) {
      all.addAll(document.manifest().requires());
    }
    return all;
  }

  private static List<SkillDocument> chain(SkillService skills, String name, String fallbackBase) {
    List<SkillDocument> chain = new ArrayList<>();
    Set<String> seen = new LinkedHashSet<>();
    SkillDocument current = skills.load(name);
    while (current != null) {
      if (!seen.add(current.name()) || chain.size() >= MAX_DEPTH) {
        throw new IllegalStateException("Skill '" + name + "' extends itself or nests too deeply");
      }
      chain.add(0, current);
      String next = current.manifest().extendsSkill();
      if (next == null && chain.size() == 1 && fallbackBase != null && !fallbackBase.equals(name)) {
        next = fallbackBase;
      }
      current = next == null ? null : skills.load(next);
    }
    return chain;
  }

  /** The skill text without its YAML front matter. */
  static String body(String content) {
    String normalized = content == null ? "" : content.replace("\r\n", "\n");
    if (!normalized.startsWith("---\n")) return normalized.strip();
    int closing = normalized.indexOf("\n---\n", 3);
    return closing < 0 ? normalized.strip() : normalized.substring(closing + 5).strip();
  }
}
