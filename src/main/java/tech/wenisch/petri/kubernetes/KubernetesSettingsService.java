package tech.wenisch.petri.kubernetes;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.audit.AuditLogService;
import tech.wenisch.petri.kubernetes.ClusterConfig.AuthMode;
import tech.wenisch.petri.secret.SecretCipher;

/**
 * Instance-wide defaults and per-project settings of Kubernetes workspaces. Secrets are encrypted
 * at rest and never handed back: a blank secret field keeps the stored value.
 */
@Service
public class KubernetesSettingsService {
  private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9]([-a-z0-9]*[a-z0-9])?");
  private static final Pattern DNS_SUBDOMAIN = Pattern.compile("[a-z0-9]([-a-z0-9.]*[a-z0-9])?");
  private static final Pattern QUANTITY =
      Pattern.compile("[0-9]+(\\.[0-9]+)?(m|k|M|G|T|P|E|Ki|Mi|Gi|Ti|Pi|Ei)?");
  private static final Pattern IMAGE = Pattern.compile("[^\\s-][^\\s]{0,499}");
  private static final int MAX_KUBECONFIG = 200_000;

  /** The values of the settings form. Blank optional fields inherit the instance default. */
  public record Save(
      boolean enabled, boolean clusterOverride, String authMode, String kubeconfig,
      boolean clearKubeconfig, String apiServerUrl, String token, boolean clearToken, String caPem,
      boolean insecure, String namespace, String image, String pvcSize, String storageClass,
      String cpuRequest, String cpuLimit, String memoryRequest, String memoryLimit, Long runAsUser,
      Long fsGroup, Integer idleTimeoutMinutes, String toolchain) {
    /** Without a toolchain choice: the project inherits the instance setting. */
    public Save(
        boolean enabled, boolean clusterOverride, String authMode, String kubeconfig,
        boolean clearKubeconfig, String apiServerUrl, String token, boolean clearToken, String caPem,
        boolean insecure, String namespace, String image, String pvcSize, String storageClass,
        String cpuRequest, String cpuLimit, String memoryRequest, String memoryLimit, Long runAsUser,
        Long fsGroup, Integer idleTimeoutMinutes) {
      this(enabled, clusterOverride, authMode, kubeconfig, clearKubeconfig, apiServerUrl, token,
          clearToken, caPem, insecure, namespace, image, pvcSize, storageClass, cpuRequest, cpuLimit,
          memoryRequest, memoryLimit, runAsUser, fsGroup, idleTimeoutMinutes, null);
    }
  }

  private final KubernetesWorkspaceConfigRepository configs;
  private final SecretCipher cipher;
  private final AuditLogService audit;
  private final Clock clock;

  @Autowired
  public KubernetesSettingsService(
      KubernetesWorkspaceConfigRepository configs, SecretCipher cipher, AuditLogService audit) {
    this(configs, cipher, audit, Clock.systemUTC());
  }

  KubernetesSettingsService(
      KubernetesWorkspaceConfigRepository configs, SecretCipher cipher, AuditLogService audit,
      Clock clock) {
    this.configs = configs;
    this.cipher = cipher;
    this.audit = audit;
    this.clock = clock;
  }

  // ---- reading ---------------------------------------------------------------------------

  /** The instance defaults; an empty, unsaved row when none were stored yet. */
  @Transactional(readOnly = true)
  public KubernetesWorkspaceConfig instance() {
    return configs.findById(KubernetesWorkspaceConfig.INSTANCE_ID)
        .orElseGet(() -> KubernetesWorkspaceConfig.instance(Instant.now(clock)));
  }

  /** An unsaved, switched-off row for the settings form of a project without settings yet. */
  public KubernetesWorkspaceConfig blankProject(UUID projectId) {
    return KubernetesWorkspaceConfig.forProject(projectId, Instant.now(clock));
  }

  @Transactional(readOnly = true)
  public Optional<KubernetesWorkspaceConfig> project(UUID projectId) {
    return configs.findByProjectId(projectId);
  }

  /** Whether the project's workspace lives in a pod. */
  @Transactional(readOnly = true)
  public boolean enabled(UUID projectId) {
    return configs.findByProjectId(projectId).map(KubernetesWorkspaceConfig::isEnabled).orElse(false);
  }

  /** The cluster the project uses, or the instance cluster; null when none is configured. */
  @Transactional(readOnly = true)
  public ClusterConfig clusterFor(UUID projectIdOrNull) {
    KubernetesWorkspaceConfig source = instance();
    if (projectIdOrNull != null) {
      Optional<KubernetesWorkspaceConfig> project = configs.findByProjectId(projectIdOrNull);
      if (project.isPresent() && project.get().isClusterOverride()) source = project.get();
    }
    return cluster(source);
  }

  /** The complete settings of an enabled project, or an explanation of what is missing. */
  @Transactional(readOnly = true)
  public EffectiveSettings effective(UUID projectId) {
    KubernetesWorkspaceConfig project = configs.findByProjectId(projectId)
        .filter(KubernetesWorkspaceConfig::isEnabled)
        .orElseThrow(() -> new IllegalStateException(
            "This project's workspace does not run in Kubernetes"));
    KubernetesWorkspaceConfig defaults = instance();
    ClusterConfig cluster = cluster(project.isClusterOverride() ? project : defaults);
    if (cluster == null) {
      throw new IllegalStateException(project.isClusterOverride()
          ? "The project's own Kubernetes cluster is not configured completely"
          : "No Kubernetes cluster is configured. An administrator sets the default under "
              + "Administration > Kubernetes, or the project can use its own cluster.");
    }
    PodSettings pod = new PodSettings(
        first(project.getNamespace(), defaults.getNamespace()),
        first(project.getImage(), defaults.getImage()),
        firstOr(project.getPvcSize(), defaults.getPvcSize(), PodSettings.DEFAULT_PVC_SIZE),
        first(project.getStorageClass(), defaults.getStorageClass()),
        first(project.getCpuRequest(), defaults.getCpuRequest()),
        first(project.getCpuLimit(), defaults.getCpuLimit()),
        first(project.getMemoryRequest(), defaults.getMemoryRequest()),
        first(project.getMemoryLimit(), defaults.getMemoryLimit()),
        project.getRunAsUser() != null ? project.getRunAsUser() : defaults.getRunAsUser(),
        project.getFsGroup() != null ? project.getFsGroup() : defaults.getFsGroup(),
        project.getIdleTimeoutMinutes() != null ? project.getIdleTimeoutMinutes()
            : defaults.getIdleTimeoutMinutes() != null ? defaults.getIdleTimeoutMinutes()
            : PodSettings.DEFAULT_IDLE_MINUTES,
        firstMode(project.getToolchainMode(), defaults.getToolchainMode()));
    if (pod.namespace() == null) {
      throw new IllegalStateException("Set a namespace for Kubernetes workspaces");
    }
    if (pod.image() == null) {
      throw new IllegalStateException("Choose the Docker image of the workspace pod");
    }
    return new EffectiveSettings(cluster, pod);
  }

  private ClusterConfig cluster(KubernetesWorkspaceConfig source) {
    if (source.getAuthMode() == null) return null;
    AuthMode mode = AuthMode.valueOf(source.getAuthMode());
    return switch (mode) {
      case KUBECONFIG -> source.isKubeconfigSet()
          ? new ClusterConfig(mode, cipher.decrypt(source.getKubeconfigCiphertext()), null, null,
              null, false)
          : null;
      case TOKEN -> source.getApiServerUrl() != null && source.isTokenSet()
          ? new ClusterConfig(mode, null, source.getApiServerUrl(),
              cipher.decrypt(source.getTokenCiphertext()), source.getCaPem(), source.isInsecure())
          : null;
      case IN_CLUSTER -> new ClusterConfig(mode, null, null, null, null, false);
    };
  }

  private static ToolchainMode firstMode(String primary, String fallback) {
    ToolchainMode mode = ToolchainMode.parse(primary);
    if (mode == null) mode = ToolchainMode.parse(fallback);
    return mode != null ? mode : ToolchainMode.DEFAULT;
  }

  private static String first(String primary, String fallback) {
    return primary != null ? primary : fallback;
  }

  private static String firstOr(String primary, String fallback, String last) {
    return primary != null ? primary : fallback != null ? fallback : last;
  }

  // ---- writing ---------------------------------------------------------------------------

  @Transactional
  public KubernetesWorkspaceConfig saveInstance(Save input, String actor) {
    KubernetesWorkspaceConfig config = configs.findById(KubernetesWorkspaceConfig.INSTANCE_ID)
        .orElseGet(() -> KubernetesWorkspaceConfig.instance(Instant.now(clock)));
    apply(config, input, true, actor);
    configs.save(config);
    audit.record(actor, "KUBERNETES_CLUSTER_SETTINGS_SAVED", "KUBERNETES_SETTINGS", config.getId(),
        describe(config));
    return config;
  }

  @Transactional
  public KubernetesWorkspaceConfig saveProject(UUID projectId, Save input, String actor) {
    KubernetesWorkspaceConfig config = configs.findByProjectId(projectId)
        .orElseGet(() -> KubernetesWorkspaceConfig.forProject(projectId, Instant.now(clock)));
    apply(config, input, false, actor);
    configs.save(config);
    audit.record(actor, "PROJECT_KUBERNETES_SETTINGS_SAVED", "PROJECT", projectId,
        (config.isEnabled() ? "Enabled" : "Disabled") + " Kubernetes workspace; " + describe(config));
    return config;
  }

  private void apply(KubernetesWorkspaceConfig config, Save input, boolean instance, String actor) {
    boolean ownCluster = instance || input.clusterOverride();
    String mode = ownCluster ? blank(input.authMode()) : null;
    if (ownCluster && mode != null) {
      try {
        AuthMode.valueOf(mode.toUpperCase(Locale.ROOT));
        mode = mode.toUpperCase(Locale.ROOT);
      } catch (IllegalArgumentException exception) {
        throw new IllegalArgumentException("Choose how Petri reaches the cluster");
      }
    }
    String url = ownCluster ? validUrl(blank(input.apiServerUrl())) : null;
    String ca = ownCluster ? validCa(blank(input.caPem())) : null;
    String kubeconfig = ownCluster ? blank(input.kubeconfig()) : null;
    if (kubeconfig != null) validateKubeconfig(kubeconfig);
    String token = ownCluster ? blank(input.token()) : null;

    String namespace = optional(input.namespace(), NAMESPACE, 63, "Namespace");
    String image = blank(input.image());
    if (image != null && !IMAGE.matcher(image).matches()) {
      throw new IllegalArgumentException("Image must be a single image reference such as "
          + "registry.example.com/team/workspace:1.0");
    }
    String pvcSize = optional(input.pvcSize(), QUANTITY, 32, "Volume size");
    String storageClass = optional(input.storageClass(), DNS_SUBDOMAIN, 253, "Storage class");
    String cpuRequest = optional(input.cpuRequest(), QUANTITY, 32, "CPU request");
    String cpuLimit = optional(input.cpuLimit(), QUANTITY, 32, "CPU limit");
    String memoryRequest = optional(input.memoryRequest(), QUANTITY, 32, "Memory request");
    String memoryLimit = optional(input.memoryLimit(), QUANTITY, 32, "Memory limit");
    requireId(input.runAsUser(), "User ID");
    requireId(input.fsGroup(), "File system group");
    Integer idle = input.idleTimeoutMinutes();
    if (idle != null && (idle < 1 || idle > 10_080)) {
      throw new IllegalArgumentException("Idle timeout must be between 1 minute and 7 days");
    }
    String toolchain = blank(input.toolchain());
    if (toolchain != null) {
      try {
        toolchain = ToolchainMode.parse(toolchain).name();
      } catch (IllegalArgumentException exception) {
        throw new IllegalArgumentException("Choose off, fallback or prefer for the bundled tools");
      }
    }
    if (!instance && input.enabled()) {
      if (!input.clusterOverride() && instance().getAuthMode() == null) {
        throw new IllegalArgumentException("No default cluster is configured yet. Ask an "
            + "administrator, or use this project's own cluster.");
      }
      if (image == null && instance().getImage() == null) {
        throw new IllegalArgumentException("Choose the Docker image of the workspace pod");
      }
      if (namespace == null && instance().getNamespace() == null) {
        throw new IllegalArgumentException("Set the namespace of the workspace pod");
      }
    }

    config.update(instance ? false : input.enabled(), !instance && input.clusterOverride(), mode, url,
        ca, ownCluster && input.insecure(), namespace, image, pvcSize, storageClass, cpuRequest,
        cpuLimit, memoryRequest, memoryLimit, input.runAsUser(), input.fsGroup(), idle, toolchain, actor,
        Instant.now(clock));

    if (!ownCluster || input.clearKubeconfig() || mode != null && !"KUBECONFIG".equals(mode)) {
      config.kubeconfigCiphertext(null);
    }
    if (ownCluster && kubeconfig != null) config.kubeconfigCiphertext(cipher.encrypt(kubeconfig));
    if (!ownCluster || input.clearToken() || mode != null && !"TOKEN".equals(mode)) {
      config.tokenCiphertext(null);
    }
    if (ownCluster && token != null) config.tokenCiphertext(cipher.encrypt(token));
    if (ownCluster && "KUBECONFIG".equals(mode) && !config.isKubeconfigSet()) {
      throw new IllegalArgumentException("Paste the kubeconfig file");
    }
    if (ownCluster && "TOKEN".equals(mode) && (url == null || !config.isTokenSet())) {
      throw new IllegalArgumentException("Enter the API server URL and a token");
    }
  }

  // ---- validation helpers (package-private for tests) -------------------------------------

  static String blank(String value) {
    return value == null || value.isBlank() ? null : value.strip();
  }

  private static String optional(String value, Pattern pattern, int max, String label) {
    String text = blank(value);
    if (text == null) return null;
    if (text.length() > max || !pattern.matcher(text).matches()) {
      throw new IllegalArgumentException(label + " is invalid");
    }
    return text;
  }

  private static void requireId(Long value, String label) {
    if (value != null && (value < 0 || value > Integer.MAX_VALUE)) {
      throw new IllegalArgumentException(label + " must be between 0 and 2147483647");
    }
  }

  static String validUrl(String value) {
    if (value == null) return null;
    try {
      URI uri = new URI(value);
      String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
      if (!(scheme.equals("https") || scheme.equals("http")) || uri.getHost() == null
          || uri.getUserInfo() != null || value.length() > 500) {
        throw new IllegalArgumentException(
            "API server URL must look like https://kubernetes.example.com:6443");
      }
      return value.replaceAll("/+$", "");
    } catch (URISyntaxException exception) {
      throw new IllegalArgumentException(
          "API server URL must look like https://kubernetes.example.com:6443");
    }
  }

  private static String validCa(String value) {
    if (value == null) return null;
    if (!value.contains("BEGIN CERTIFICATE") || value.length() > 100_000) {
      throw new IllegalArgumentException("The CA bundle must be PEM encoded certificates");
    }
    return value;
  }

  static void validateKubeconfig(String value) {
    if (value.length() > MAX_KUBECONFIG || !value.contains("clusters:")) {
      throw new IllegalArgumentException("That does not look like a kubeconfig file");
    }
  }

  private static String describe(KubernetesWorkspaceConfig config) {
    // Names only: never credentials.
    return "mode=" + config.getAuthMode() + ", namespace=" + config.getNamespace()
        + ", image=" + config.getImage();
  }
}
