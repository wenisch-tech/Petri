package tech.wenisch.petri.config;

import java.net.Socket;
import java.net.http.HttpClient;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Global TLS verification switch. {@code IGNORE_TLS=true} disables certificate and hostname
 * verification for every outbound HTTPS connection Petri makes (identity providers, Git provider
 * APIs, model providers and Git subprocesses). Meant for self-hosted environments with private CAs.
 */
@Component
public class TlsSettings {
  private static final Logger log = LoggerFactory.getLogger(TlsSettings.class);

  private final boolean ignoreTls;

  public TlsSettings(@Value("${IGNORE_TLS:false}") boolean ignoreTls) {
    this.ignoreTls = ignoreTls;
    if (ignoreTls) {
      installJvmWideDefaults();
      log.warn("IGNORE_TLS is enabled: TLS certificate and hostname verification are disabled for "
          + "all outbound HTTPS connections. Do not use this on untrusted networks.");
    }
  }

  public static TlsSettings verifying() {
    return new TlsSettings(false);
  }

  public boolean ignoreTls() {
    return ignoreTls;
  }

  /** Applies the trust-all context to the builder when {@code IGNORE_TLS} is set. */
  public HttpClient.Builder apply(HttpClient.Builder builder) {
    return ignoreTls ? insecure(builder) : builder;
  }

  public static HttpClient.Builder insecure(HttpClient.Builder builder) {
    SSLParameters parameters = new SSLParameters();
    parameters.setEndpointIdentificationAlgorithm("");
    return builder.sslContext(insecureSslContext()).sslParameters(parameters);
  }

  /**
   * Installs trust-all defaults for {@link HttpsURLConnection}, which covers OIDC discovery and JWKS
   * retrieval. Used by the OIDC-only {@code OIDC_INSECURE_SKIP_TLS_VERIFY} switch as well.
   */
  public static void installHttpsUrlConnectionDefaults() {
    HttpsURLConnection.setDefaultSSLSocketFactory(insecureSslContext().getSocketFactory());
    HostnameVerifier trustAll = (hostname, session) -> true;
    HttpsURLConnection.setDefaultHostnameVerifier(trustAll);
  }

  private static void installJvmWideDefaults() {
    installHttpsUrlConnectionDefaults();
    SSLContext.setDefault(insecureSslContext());
    System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
  }

  public static SSLContext insecureSslContext() {
    try {
      // An X509ExtendedTrustManager stops the JDK from layering its own hostname check on top.
      TrustManager[] trustAll = {new X509ExtendedTrustManager() {
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
        @Override public void checkClientTrusted(
            X509Certificate[] chain, String authType, Socket socket) {}
        @Override public void checkServerTrusted(
            X509Certificate[] chain, String authType, Socket socket) {}
        @Override public void checkClientTrusted(
            X509Certificate[] chain, String authType, SSLEngine engine) {}
        @Override public void checkServerTrusted(
            X509Certificate[] chain, String authType, SSLEngine engine) {}
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
      }};
      SSLContext context = SSLContext.getInstance("TLS");
      context.init(null, trustAll, new SecureRandom());
      return context;
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("Unable to configure the TLS verification bypass", exception);
    }
  }
}
