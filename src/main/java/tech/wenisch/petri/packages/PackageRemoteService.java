package tech.wenisch.petri.packages;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.audit.AuditLogService;
import tech.wenisch.petri.secret.SecretCipher;

/**
 * The package remotes of the instance and of projects, the CA bundle, and what applies to a run.
 * A project's remotes of an ecosystem replace the instance's ones of that ecosystem; a project
 * with none inherits them.
 */
@Service
public class PackageRemoteService {
  static final String KEEP_PASSWORD = "********";
  private static final Pattern SAFE_TEXT = Pattern.compile("[^\\p{Cntrl}]{1,200}");

  /** What a run needs: remotes with decrypted passwords and the trust material. */
  public record Effective(List<PackageToolConfig.Remote> remotes, PackageToolConfig.Trust trust) {
    public boolean empty() {
      return remotes.isEmpty() && trust.pemBundle() == null && !trust.ignoreTlsErrors();
    }
  }

  public record Save(
      PackageEcosystem ecosystem, String name, String url, PackageRemoteMode mode, String username,
      String password, boolean clearPassword, String mirrorOf, boolean insecure, boolean enabled,
      int position) {}

  private final PackageRemoteRepository remotes;
  private final PackageTlsSettingsRepository tls;
  private final SecretCipher cipher;
  private final AuditLogService audit;
  private final Path pkiDirectory;
  private final PackageRemoteTester tester;

  PackageRemoteService(
      PackageRemoteRepository remotes, PackageTlsSettingsRepository tls, SecretCipher cipher,
      AuditLogService audit, PackageRemoteTester tester,
      @Value("${petri.packages.pki-dir:./data/pki}") String pkiDirectory) {
    this.tester = tester;
    this.remotes = remotes;
    this.tls = tls;
    this.cipher = cipher;
    this.audit = audit;
    this.pkiDirectory = Path.of(pkiDirectory).toAbsolutePath().normalize();
  }

  @Transactional(readOnly = true)
  public List<PackageRemote> instanceRemotes() {
    return remotes.findAllByProjectIdIsNullOrderByEcosystemAscPositionAscCreatedAtAsc();
  }

  @Transactional(readOnly = true)
  public List<PackageRemote> projectRemotes(UUID projectId) {
    return remotes.findAllByProjectIdOrderByEcosystemAscPositionAscCreatedAtAsc(projectId);
  }

  @Transactional
  public PackageRemote save(UUID projectId, UUID id, Save input, String actor) {
    String name = input.name() == null ? "" : input.name().strip();
    if (!SAFE_TEXT.matcher(name).matches()) throw new IllegalArgumentException("Give the remote a name");
    String url = validatedUrl(input.url(), input.ecosystem());
    String username = blankToNull(input.username());
    if (username != null && !SAFE_TEXT.matcher(username).matches()) {
      throw new IllegalArgumentException("The user name contains characters that are not allowed");
    }
    String mirrorOf = blankToNull(input.mirrorOf());
    if (mirrorOf != null && !Pattern.matches("[A-Za-z0-9_.,*!:\\- ]{1,120}", mirrorOf)) {
      throw new IllegalArgumentException("Mirror of: use repository ids such as central or *");
    }
    Instant now = Instant.now();
    PackageRemote remote;
    if (id == null) {
      remote = new PackageRemote(UUID.randomUUID(), projectId, now);
    } else {
      remote = remotes.findById(id).filter(value -> java.util.Objects.equals(value.getProjectId(), projectId))
          .orElseThrow(() -> new IllegalArgumentException("The remote does not exist"));
    }
    remote.update(input.ecosystem(), name, url,
        input.mode() == null ? PackageRemoteMode.REPLACE : input.mode(), username,
        input.ecosystem() == PackageEcosystem.MAVEN ? mirrorOf : null, input.insecure(),
        input.enabled(), input.position(), actor, now);
    String password = input.password();
    if (input.clearPassword() || username == null) {
      remote.password(null);
    } else if (password != null && !password.isEmpty() && !KEEP_PASSWORD.equals(password)) {
      remote.password(cipher.encrypt(password));
    }
    remotes.save(remote);
    audit.record(actor, id == null ? "PACKAGE_REMOTE_CREATED" : "PACKAGE_REMOTE_UPDATED",
        "PACKAGE_REMOTE", remote.getId(), input.ecosystem() + " " + url);
    return remote;
  }

  /** Checks one saved remote of the instance (projectId null) or of a project. */
  @Transactional(readOnly = true)
  public PackageRemoteTester.Result test(UUID projectId, UUID id) {
    PackageRemote remote = remotes.findById(id)
        .filter(value -> java.util.Objects.equals(value.getProjectId(), projectId))
        .orElseThrow(() -> new IllegalArgumentException("The remote does not exist"));
    String password = remote.passwordCiphertext() == null ? null
        : cipher.decrypt(remote.passwordCiphertext());
    PackageToolConfig.Remote resolved = new PackageToolConfig.Remote(remote.getEcosystem(),
        remote.getUrl(), remote.getMode(), remote.getUsername(), password, remote.getMirrorOf(),
        remote.isInsecure());
    return tester.test(resolved, effective(projectId).trust());
  }

  @Transactional
  public void delete(UUID projectId, UUID id, String actor) {
    PackageRemote remote = remotes.findById(id)
        .filter(value -> java.util.Objects.equals(value.getProjectId(), projectId))
        .orElseThrow(() -> new IllegalArgumentException("The remote does not exist"));
    remotes.delete(remote);
    audit.record(actor, "PACKAGE_REMOTE_DELETED", "PACKAGE_REMOTE", id, remote.getEcosystem() + " " + remote.getUrl());
  }

  @Transactional(readOnly = true)
  public PackageTlsSettings tlsSettings() {
    return tls.findById(PackageTlsSettings.ID).orElseGet(() -> new PackageTlsSettings(Instant.now()));
  }

  @Transactional
  public void saveTls(String caPem, boolean ignoreTlsErrors, String actor) {
    String pem = blankToNull(caPem);
    if (pem != null) PackageTrustStore.validate(pem);
    Instant now = Instant.now();
    PackageTlsSettings settings = tls.findById(PackageTlsSettings.ID)
        .orElseGet(() -> new PackageTlsSettings(now));
    settings.update(pem, ignoreTlsErrors, actor, now);
    tls.save(settings);
    audit.record(actor, "PACKAGE_TLS_SAVED", "PACKAGE_TLS", PackageTlsSettings.ID,
        (pem == null ? "no extra CA" : "extra CA bundle") + (ignoreTlsErrors ? ", TLS errors ignored" : ""));
  }

  /** What applies to a run of the project (or to the instance when {@code projectId} is null). */
  @Transactional(readOnly = true)
  public Effective effective(UUID projectId) {
    List<PackageRemote> chosen = new ArrayList<>(instanceRemotes());
    if (projectId != null) {
      List<PackageRemote> own = projectRemotes(projectId);
      for (PackageEcosystem ecosystem : PackageEcosystem.values()) {
        if (own.stream().anyMatch(value -> value.getEcosystem() == ecosystem && value.isEnabled())) {
          chosen.removeIf(value -> value.getEcosystem() == ecosystem);
        }
      }
      chosen.addAll(own);
    }
    List<PackageToolConfig.Remote> resolved = new ArrayList<>();
    for (PackageRemote remote : chosen) {
      if (!remote.isEnabled()) continue;
      String password = remote.passwordCiphertext() == null ? null
          : cipher.decrypt(remote.passwordCiphertext());
      resolved.add(new PackageToolConfig.Remote(remote.getEcosystem(), remote.getUrl(),
          remote.getMode(), remote.getUsername(), password, remote.getMirrorOf(), remote.isInsecure()));
    }
    PackageTlsSettings settings = tls.findById(PackageTlsSettings.ID).orElse(null);
    PackageToolConfig.Trust trust = PackageToolConfig.Trust.NONE;
    if (settings != null) {
      try {
        trust = PackageTrustStore.prepare(pkiDirectory, settings.getCaPem(), settings.isIgnoreTlsErrors());
      } catch (IOException exception) {
        throw new IllegalStateException(exception.getMessage(), exception);
      }
    }
    return new Effective(List.copyOf(resolved), trust);
  }

  private static String validatedUrl(String value, PackageEcosystem ecosystem) {
    String url = value == null ? "" : value.strip();
    URI uri;
    try {
      uri = URI.create(url);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("The URL is not valid");
    }
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
    if (!(scheme.equals("https") || scheme.equals("http")) || uri.getHost() == null
        || uri.getUserInfo() != null || url.length() > 500) {
      throw new IllegalArgumentException(
          "The URL must start with https:// (or http://) and must not contain credentials; "
              + "enter them in the user name and password fields");
    }
    return url;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }
}
