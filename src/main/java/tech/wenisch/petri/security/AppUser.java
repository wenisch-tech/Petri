package tech.wenisch.petri.security;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Entity
@Table(name = "app_user")
public class AppUser {
  @Id private UUID id;

  @Column(nullable = false, unique = true, length = 320)
  private String email;

  @Column(name = "password_hash", length = 200)
  private String passwordHash;

  @Column(nullable = false, length = 32)
  private String role;

  @Column(name = "password_change_required", nullable = false)
  private boolean passwordChangeRequired;

  @Column(nullable = false)
  private boolean enabled;

  @Column(name = "created_at", nullable = false)
  private Instant createdAt;

  protected AppUser() {}

  public AppUser(UUID id, String email, String passwordHash, String role) {
    this(id, email, passwordHash, role, true);
  }

  public static AppUser oidcUser(UUID id, String email, String role) {
    return new AppUser(id, email, null, role, false);
  }

  private AppUser(UUID id, String email, String passwordHash, String role, boolean passwordChangeRequired) {
    this.id = id;
    this.email = email.trim().toLowerCase(Locale.ROOT);
    this.passwordHash = passwordHash;
    this.role = role;
    this.passwordChangeRequired = passwordChangeRequired;
    this.enabled = true;
    this.createdAt = Instant.now();
  }

  public void changeRole(String value) { this.role = value; }
  public void changeEnabled(boolean value) { this.enabled = value; }

  /** Stores an already encoded password; a person set it for them, so no forced change. */
  public void changePassword(String encodedPassword) {
    this.passwordHash = encodedPassword;
    this.passwordChangeRequired = false;
  }

  public UUID getId() { return id; }
  public String getEmail() { return email; }
  @JsonIgnore
  public String getPasswordHash() { return passwordHash; }
  public boolean hasLocalPassword() { return passwordHash != null; }
  public String getRole() { return role; }
  public boolean isPasswordChangeRequired() { return passwordChangeRequired; }
  public boolean isEnabled() { return enabled; }
  public Instant getCreatedAt() { return createdAt; }
}
