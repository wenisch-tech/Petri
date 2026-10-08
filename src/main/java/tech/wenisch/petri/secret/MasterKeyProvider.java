package tech.wenisch.petri.secret;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Set;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import tech.wenisch.petri.config.PetriProperties;

@Component
public class MasterKeyProvider {
  private static final int KEY_BYTES = 32;
  private static final Set<PosixFilePermission> OWNER_ONLY =
      EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);

  private final byte[] key;

  public MasterKeyProvider(PetriProperties properties) {
    PetriProperties.Secrets secrets = properties.secrets();
    key = secrets.isConfigured()
        ? decodeAndValidate(secrets.masterKey(), "PETRI_SECRETS_MASTER_KEY")
        : loadOrCreate(Path.of(secrets.masterKeyFile()));
  }

  SecretKeySpec aesKey() {
    return new SecretKeySpec(key.clone(), "AES");
  }

  private static byte[] loadOrCreate(Path configuredPath) {
    Path keyFile = configuredPath.toAbsolutePath().normalize();
    Path parent = keyFile.getParent();
    if (parent == null) throw new IllegalStateException("Master-key file needs a parent directory");
    Path lockFile = parent.resolve(keyFile.getFileName() + ".lock");
    try {
      Files.createDirectories(parent);
      rejectSymbolicLink(keyFile);
      rejectSymbolicLink(lockFile);
      try (FileChannel channel = FileChannel.open(
              lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
          FileLock ignored = channel.lock()) {
        restrictOwnerOnly(lockFile);
        rejectSymbolicLink(keyFile);
        if (!Files.exists(keyFile, LinkOption.NOFOLLOW_LINKS)) createKeyFile(keyFile);
        if (!Files.isRegularFile(keyFile, LinkOption.NOFOLLOW_LINKS)) {
          throw new IllegalStateException("Master-key path is not a regular file: " + keyFile);
        }
        restrictOwnerOnly(keyFile);
        return decodeAndValidate(Files.readString(keyFile, StandardCharsets.UTF_8),
            "master-key file " + keyFile);
      }
    } catch (IOException exception) {
      throw new IllegalStateException("Could not load or create master-key file " + keyFile, exception);
    }
  }

  private static void createKeyFile(Path keyFile) throws IOException {
    try {
      Files.createFile(keyFile, PosixFilePermissions.asFileAttribute(OWNER_ONLY));
    } catch (UnsupportedOperationException exception) {
      Files.createFile(keyFile);
      restrictOwnerOnly(keyFile);
    }
    byte[] generated = new byte[KEY_BYTES];
    new SecureRandom().nextBytes(generated);
    String encoded = Base64.getEncoder().encodeToString(generated) + System.lineSeparator();
    Files.writeString(
        keyFile,
        encoded,
        StandardCharsets.UTF_8,
        StandardOpenOption.WRITE,
        StandardOpenOption.TRUNCATE_EXISTING);
  }

  private static byte[] decodeAndValidate(String encoded, String source) {
    byte[] decoded;
    try {
      decoded = Base64.getDecoder().decode(encoded.trim());
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException(source + " must be Base64-encoded", exception);
    }
    if (decoded.length != KEY_BYTES) {
      throw new IllegalStateException(source + " must contain exactly 32 bytes");
    }
    return decoded;
  }

  private static void rejectSymbolicLink(Path path) {
    if (Files.isSymbolicLink(path)) {
      throw new IllegalStateException("Master-key paths must not be symbolic links: " + path);
    }
  }

  private static void restrictOwnerOnly(Path path) throws IOException {
    try {
      Files.setPosixFilePermissions(path, OWNER_ONLY);
    } catch (UnsupportedOperationException exception) {
      if (!path.toFile().setReadable(false, false)
          || !path.toFile().setReadable(true, true)
          || !path.toFile().setWritable(false, false)
          || !path.toFile().setWritable(true, true)) {
        throw new IOException("Could not restrict permissions on " + path, exception);
      }
    }
  }
}
