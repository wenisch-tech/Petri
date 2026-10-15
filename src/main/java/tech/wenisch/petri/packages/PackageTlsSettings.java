package tech.wenisch.petri.packages;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** The single row of the instance's CA bundle and TLS switch for package tools. */
@Entity
@Table(name = "package_tls_settings")
public class PackageTlsSettings {
  static final UUID ID = UUID.fromString("00000000-0000-4000-8000-000000000041");

  @Id private UUID id;
  @Column(name = "ca_pem", columnDefinition = "TEXT") private String caPem;
  @Column(name = "ignore_tls_errors", nullable = false) private boolean ignoreTlsErrors;
  @Column(name = "updated_at", nullable = false) private Instant updatedAt;
  @Column(name = "updated_by", length = 320) private String updatedBy;

  protected PackageTlsSettings() {}

  PackageTlsSettings(Instant now) {
    this.id = ID;
    this.updatedAt = now;
  }

  void update(String caPem, boolean ignoreTlsErrors, String actor, Instant now) {
    this.caPem = caPem;
    this.ignoreTlsErrors = ignoreTlsErrors;
    this.updatedBy = actor;
    this.updatedAt = now;
  }

  public String getCaPem() { return caPem; }
  public boolean isIgnoreTlsErrors() { return ignoreTlsErrors; }
  public Instant getUpdatedAt() { return updatedAt; }
  public String getUpdatedBy() { return updatedBy; }
}
