package tech.wenisch.petri.kubernetes;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** How Petri reaches a cluster, with its secrets decrypted. Never rendered, logged or serialized. */
public record ClusterConfig(
    AuthMode authMode, String kubeconfig, String apiServerUrl, String token, String caPem,
    boolean insecure) {

  public enum AuthMode {
    /** A pasted kubeconfig file. */
    KUBECONFIG,
    /** An API server URL and a bearer token, optionally with the cluster CA. */
    TOKEN,
    /** The service account Petri itself runs under, when Petri runs in the cluster. */
    IN_CLUSTER
  }

  /** Identifies the connection without exposing it; used to cache one client per cluster. */
  public String cacheKey() {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      for (Object part : new Object[] {authMode, kubeconfig, apiServerUrl, token, caPem, insecure}) {
        digest.update(String.valueOf(part).getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
      }
      return HexFormat.of().formatHex(digest.digest());
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  @Override
  public String toString() {
    return "ClusterConfig[" + authMode + "]";
  }
}
