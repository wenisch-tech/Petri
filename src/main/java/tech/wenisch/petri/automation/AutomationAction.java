package tech.wenisch.petri.automation;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/** One row of the actions an automation can run. */
@Entity
@Table(name = "automation_action")
public class AutomationAction {
  @Id private String id;
  @Column(nullable = false, length = 120) private String title;
  @Column(nullable = false, length = 1000) private String description;
  @Column(nullable = false, length = 64) private String skill;
  @Column(name = "base_skill", nullable = false, length = 64) private String baseSkill;
  @Column(nullable = false) private boolean comments;
  @Column(nullable = false) private boolean improves;
  @Column(name = "branch_based", nullable = false) private boolean branchBased;
  @Column(nullable = false) private boolean merges;
  @Column(nullable = false) private boolean vulnerability;
  @Column(nullable = false, length = 64) private String triggers;
  @Column(nullable = false) private boolean builtin;
  @Column(name = "created_at", nullable = false) private Instant createdAt;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;

  protected AutomationAction() {}

  AutomationAction(AutomationKind kind, Instant now) {
    this.id = kind.id();
    this.createdAt = now;
    update(kind, now);
  }

  void update(AutomationKind kind, Instant now) {
    this.title = kind.title();
    this.description = kind.description();
    this.skill = kind.skill();
    this.baseSkill = kind.baseSkill();
    this.comments = kind.comments();
    this.improves = kind.improves();
    this.branchBased = kind.branchBased();
    this.merges = kind.merges();
    this.vulnerability = kind.vulnerability();
    this.triggers = kind.triggers().stream().map(Enum::name).sorted()
        .collect(Collectors.joining(","));
    this.builtin = kind.builtin();
    this.updatedAt = now;
  }

  AutomationKind toKind() {
    Set<AutomationTrigger> parsed = EnumSet.noneOf(AutomationTrigger.class);
    for (String name : triggers.split(",")) {
      try {
        parsed.add(AutomationTrigger.valueOf(name.strip()));
      } catch (IllegalArgumentException ignored) {
        // a trigger that no longer exists is simply not offered
      }
    }
    if (parsed.isEmpty()) parsed.add(AutomationTrigger.CRON);
    return new AutomationKind(id, title, description, skill, baseSkill, comments, improves,
        branchBased, merges, vulnerability, parsed, builtin);
  }

  public String getId() { return id; }
  public boolean isBuiltin() { return builtin; }
  public String getTitle() { return title; }
  public String getDescription() { return description; }
  public String getSkill() { return skill; }
  public boolean isBranchBased() { return branchBased; }
  public boolean isVulnerability() { return vulnerability; }
  public boolean isComments() { return comments; }
  public boolean isImproves() { return improves; }
  public boolean isMerges() { return merges; }
}
