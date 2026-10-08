package tech.wenisch.petri.project;

import java.text.Normalizer;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.audit.AuditLogService;
import tech.wenisch.petri.security.AppUser;
import tech.wenisch.petri.security.AppUserRepository;

@Service
public class ProjectService {
  private static final int MAX_SLUG_BASE_LENGTH = 72;

  private final ProjectRepository projects;
  private final AppUserRepository users;
  private final AuditLogService auditLog;
  private final Clock clock;

  @Autowired
  public ProjectService(
      ProjectRepository projects, AppUserRepository users, AuditLogService auditLog) {
    this(projects, users, auditLog, Clock.systemUTC());
  }

  ProjectService(
      ProjectRepository projects,
      AppUserRepository users,
      AuditLogService auditLog,
      Clock clock) {
    this.projects = projects;
    this.users = users;
    this.auditLog = auditLog;
    this.clock = clock;
  }

  @Transactional
  public Project create(CreateProjectCommand command, String actorEmail) {
    String name = required(command.name(), "Project name", 120);
    String repositoryUri = required(command.repositoryUri(), "Repository URI", 2048);
    String defaultBranch = optional(command.defaultBranch(), "main", 255);
    AppUser actor = users.findByEmailIgnoreCase(actorEmail)
        .orElseThrow(() -> new IllegalArgumentException("Unknown project owner"));

    Project project = new Project(
        UUID.randomUUID(),
        name,
        availableSlug(name),
        repositoryUri,
        defaultBranch,
        actor.getId(),
        Instant.now(clock));
    projects.save(project);
    auditLog.record(
        actor.getEmail(),
        "PROJECT_CREATED",
        "PROJECT",
        project.getId(),
        "Created project " + project.getName());
    return project;
  }

  private String availableSlug(String name) {
    String base = Normalizer.normalize(name, Normalizer.Form.NFD)
        .replaceAll("\\p{M}", "")
        .toLowerCase(Locale.ROOT)
        .replaceAll("[^a-z0-9]+", "-")
        .replaceAll("(^-|-$)", "");
    if (base.isBlank()) base = "project";
    if (base.length() > MAX_SLUG_BASE_LENGTH) {
      base = base.substring(0, MAX_SLUG_BASE_LENGTH).replaceFirst("-+$", "");
    }
    String candidate = base;
    int suffix = 2;
    while (projects.existsBySlug(candidate)) candidate = base + "-" + suffix++;
    return candidate;
  }

  private static String required(String value, String label, int maxLength) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required");
    return limited(value.trim(), label, maxLength);
  }

  private static String optional(String value, String fallback, int maxLength) {
    return value == null || value.isBlank() ? fallback : limited(value.trim(), "Value", maxLength);
  }

  private static String limited(String value, String label, int maxLength) {
    if (value.length() > maxLength) throw new IllegalArgumentException(label + " is too long");
    return value;
  }
}
