package tech.wenisch.petri.packages;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.GeneralSecurityException;
import java.security.cert.Certificate;
import java.util.Base64;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;

/**
 * Reads the certificate chain a server presents, without verifying it. Used for remotes marked
 * insecure whose tool has no switch to skip verification (Go): the chain is then trusted explicitly.
 */
final class PackageServerCertificates {
  private static final int TIMEOUT_MILLIS = 10_000;

  private PackageServerCertificates() {}

  /** The presented chain as PEM, or null for plain HTTP. */
  static String fetch(String url) throws IOException {
    URI uri = URI.create(url);
    if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) return null;
    int port = uri.getPort() > 0 ? uri.getPort() : 443;
    try {
      SSLContext context = SSLContext.getInstance("TLS");
      context.init(null, new TrustManager[] {new PackageRemoteTester.TrustAll()}, null);
      try (SSLSocket socket = (SSLSocket) context.getSocketFactory().createSocket()) {
        socket.connect(new InetSocketAddress(uri.getHost(), port), TIMEOUT_MILLIS);
        socket.setSoTimeout(TIMEOUT_MILLIS);
        socket.startHandshake();
        StringBuilder pem = new StringBuilder();
        for (Certificate certificate : socket.getSession().getPeerCertificates()) {
          pem.append("-----BEGIN CERTIFICATE-----\n")
              .append(Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(certificate.getEncoded()))
              .append("\n-----END CERTIFICATE-----\n");
        }
        return pem.toString();
      }
    } catch (GeneralSecurityException exception) {
      throw new IOException(exception.getMessage(), exception);
    }
  }
}
