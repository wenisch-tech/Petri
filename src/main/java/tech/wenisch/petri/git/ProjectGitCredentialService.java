package tech.wenisch.petri.git;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.audit.AuditLogService;
import tech.wenisch.petri.project.Project;
import tech.wenisch.petri.project.ProjectRepository;
import tech.wenisch.petri.secret.SecretCipher;
import tech.wenisch.petri.security.AppUser;
import tech.wenisch.petri.security.AppUserRepository;

@Service
public class ProjectGitCredentialService {
  private static final int MAX_SECRET_LENGTH = 12000;

  private final ProjectGitCredentialRepository credentials;
  private final ProjectRepository projects;
  private final AppUserRepository users;
  private final SecretCipher cipher;
  private final AuditLogService auditLog;
  private final Clock clock;

  @Autowired
  public ProjectGitCredentialService(
      ProjectGitCredentialRepository credentials,
      ProjectRepository projects,
      AppUserRepository users,
      SecretCipher cipher,
      AuditLogService auditLog) {
    this(credentials, projects, users, cipher, auditLog, Clock.systemUTC());
  }

  ProjectGitCredentialService(
      ProjectGitCredentialRepository credentials,
      ProjectRepository projects,
      AppUserRepository users,
      SecretCipher cipher,
      AuditLogService auditLog,
      Clock clock) {
    this.credentials = credentials;
    this.projects = projects;
    this.users = users;
    this.cipher = cipher;
    this.auditLog = auditLog;
    this.clock = clock;
  }

  @Transactional(readOnly = true)
  public GitCredentialStatus status(UUID projectId) {
    return credentials.findByProjectId(projectId)
        .map(value -> new GitCredentialStatus(
            true, value.authenticationType(), value.username(), value.updatedAt()))
        .orElseGet(GitCredentialStatus::notConfigured);
  }

  @Transactional(readOnly = true)
  Optional<GitCredentialMaterial> material(UUID projectId) {
    return credentials.findByProjectId(projectId)
        .map(value -> new GitCredentialMaterial(
            value.authenticationType(), value.username(), cipher.decrypt(value.secretCiphertext())));
  }

  @Transactional
  public GitCredentialStatus save(
      UUID projectId, SaveGitCredentialCommand command, String actorEmail) {
    Project project = project(projectId);
    AppUser actor = actor(actorEmail);
    GitAuthenticationType authenticationType = command.authenticationType();
    if (authenticationType == null) {
      throw new IllegalArgumentException("Authentication type is required");
    }
    String username = optional(command.username(), 320);
    if (authenticationType == GitAuthenticationType.USERNAME_PASSWORD && username == null) {
      throw new IllegalArgumentException("Username is required for username/password authentication");
    }
    String secret = required(command.secret(), "Secret", MAX_SECRET_LENGTH);
    String encryptedSecret = cipher.encrypt(secret);
    Instant now = Instant.now(clock);

    ProjectGitCredential credential = credentials.findByProjectId(projectId).orElse(null);
    String action;
    if (credential == null) {
      credential = new ProjectGitCredential(
          UUID.randomUUID(), projectId, authenticationType, username, encryptedSecret,
          actor.getId(), now);
      action = "GIT_CREDENTIAL_CREATED";
    } else {
      credential.replace(authenticationType, username, encryptedSecret, actor.getId(), now);
      action = "GIT_CREDENTIAL_REPLACED";
    }
    credentials.save(credential);
    auditLog.record(
        actor.getEmail(), action, "PROJECT_GIT_CREDENTIAL", credential.id(),
        "Configured Git credentials for project " + project.getName());
    return new GitCredentialStatus(true, authenticationType, username, now);
  }

  @Transactional
  public void remove(UUID projectId, String actorEmail) {
    Project project = project(projectId);
    AppUser actor = actor(actorEmail);
    credentials.findByProjectId(projectId).ifPresent(credential -> {
      credentials.delete(credential);
      auditLog.record(
          actor.getEmail(), "GIT_CREDENTIAL_REMOVED", "PROJECT_GIT_CREDENTIAL", credential.id(),
          "Removed Git credentials from project " + project.getName());
    });
  }

  private Project project(UUID projectId) {
    return projects.findById(projectId)
        .orElseThrow(() -> new IllegalArgumentException("Unknown project"));
  }

  private AppUser actor(String actorEmail) {
    return users.findByEmailIgnoreCase(actorEmail)
        .orElseThrow(() -> new IllegalArgumentException("Unknown user"));
  }

  private static String required(String value, String label, int maxLength) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " is required");
    if (value.length() > maxLength) throw new IllegalArgumentException(label + " is too long");
    return value;
  }

  private static String optional(String value, int maxLength) {
    if (value == null || value.isBlank()) return null;
    String normalized = value.trim();
    if (normalized.length() > maxLength) throw new IllegalArgumentException("Username is too long");
    return normalized;
  }
}
