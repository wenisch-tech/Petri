package tech.wenisch.petri.packages;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Collection;
import java.util.HexFormat;

/**
 * Builds, from the administrator's CA bundle, the two files package tools read: a PEM bundle (the
 * system CAs plus the extra ones) and a PKCS12 trust store (the JDK's CAs plus the extra ones) for
 * Java tools. Files are named after the bundle's hash, so they are written once.
 */
public final class PackageTrustStore {
  static final String PASSWORD = "changeit";
  private static final Path SYSTEM_PEM = Path.of("/etc/ssl/certs/ca-certificates.crt");

  private PackageTrustStore() {}

  /** Checks that the text holds at least one certificate and returns how many. */
  public static int validate(String pem) {
    try {
      return parse(pem).size();
    } catch (GeneralSecurityException exception) {
      throw new IllegalArgumentException("The CA bundle is not valid PEM certificates");
    }
  }

  public static PackageToolConfig.Trust prepare(Path directory, String pem, boolean ignoreTls)
      throws IOException {
    if (pem == null || pem.isBlank()) {
      return new PackageToolConfig.Trust(null, null, null, ignoreTls);
    }
    try {
      Collection<? extends Certificate> extra = parse(pem);
      String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(pem.getBytes(StandardCharsets.UTF_8))).substring(0, 16);
      Files.createDirectories(directory);
      Path bundle = directory.resolve("ca-" + hash + ".pem");
      Path store = directory.resolve("ca-" + hash + ".p12");
      if (!Files.isRegularFile(bundle)) {
        String system = Files.isRegularFile(SYSTEM_PEM)
            ? Files.readString(SYSTEM_PEM, StandardCharsets.UTF_8) : "";
        Files.writeString(bundle, system + "\n" + pem.strip() + "\n", StandardCharsets.UTF_8);
      }
      if (!Files.isRegularFile(store)) {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        keyStore.load(null, PASSWORD.toCharArray());
        Path cacerts = Path.of(System.getProperty("java.home", ""), "lib", "security", "cacerts");
        if (Files.isRegularFile(cacerts)) {
          KeyStore jdk = KeyStore.getInstance(cacerts.toFile(), PASSWORD.toCharArray());
          for (var aliases = jdk.aliases(); aliases.hasMoreElements();) {
            String alias = aliases.nextElement();
            Certificate certificate = jdk.getCertificate(alias);
            if (certificate != null) keyStore.setCertificateEntry("jdk-" + alias, certificate);
          }
        }
        int index = 0;
        for (Certificate certificate : extra) keyStore.setCertificateEntry("petri-" + index++, certificate);
        try (OutputStream out = Files.newOutputStream(store)) {
          keyStore.store(out, PASSWORD.toCharArray());
        }
      }
      return new PackageToolConfig.Trust(bundle, store, PASSWORD, ignoreTls);
    } catch (GeneralSecurityException exception) {
      throw new IOException("Could not prepare the CA bundle: " + exception.getMessage(), exception);
    }
  }

  private static Collection<? extends Certificate> parse(String pem) throws GeneralSecurityException {
    Collection<? extends Certificate> certificates = CertificateFactory.getInstance("X.509")
        .generateCertificates(new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)));
    if (certificates.isEmpty()) throw new GeneralSecurityException("no certificates");
    return certificates;
  }
}
