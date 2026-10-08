package tech.wenisch.petri.agent.tool;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;
import tech.wenisch.petri.config.PetriProperties;

@Service
public class WorkspaceFileService {
  private static final int MAX_DEPTH = 20;
  private static final int MAX_QUERY_LENGTH = 500;
  private static final int MAX_PREVIEW_CHARACTERS = 500;
  private static final int MAX_VISITED_PATHS = 10_000;

  private final int maxFileBytes;
  private final int maxOutputBytes;
  private final int configuredMaxSearchResults;

  public WorkspaceFileService(PetriProperties properties) {
    maxFileBytes = properties.workspace().maxToolFileBytes();
    maxOutputBytes = properties.workspace().maxToolOutputBytes();
    configuredMaxSearchResults = properties.workspace().maxSearchResults();
  }

  public ListFilesResult list(Path worktree, String requestedPath, int maxDepth) {
    if (maxDepth < 1 || maxDepth > MAX_DEPTH) {
      throw new IllegalArgumentException("maxDepth must be between 1 and " + MAX_DEPTH);
    }
    Path base = resolve(worktree, requestedPath == null ? "." : requestedPath);
    if (!Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalArgumentException("List path is not a directory");
    }
    List<FileEntry> entries = new ArrayList<>();
    int retainedBytes = 0;
    boolean truncated = false;
    try (Stream<Path> stream = Files.walk(base, maxDepth)) {
      List<Path> candidates = stream
          .filter(path -> !path.equals(base))
          .filter(path -> !isGitMetadata(worktree, path))
          .limit(MAX_VISITED_PATHS + 1L)
          .sorted(Comparator.comparing(path -> relative(worktree, path)))
          .toList();
      if (candidates.size() > MAX_VISITED_PATHS) truncated = true;
      for (Path candidate : candidates.stream().limit(MAX_VISITED_PATHS).toList()) {
        String relative = relative(worktree, candidate);
        int estimatedBytes = relative.getBytes(StandardCharsets.UTF_8).length + 64;
        if (retainedBytes + estimatedBytes > maxOutputBytes) {
          truncated = true;
          break;
        }
        entries.add(new FileEntry(relative, type(candidate), fileSize(candidate)));
        retainedBytes += estimatedBytes;
      }
    } catch (IOException exception) {
      throw new IllegalStateException("Could not list workspace files", exception);
    }
    return new ListFilesResult(List.copyOf(entries), truncated);
  }

  public ReadFileResult read(Path worktree, String requestedPath) {
    Path file = resolve(worktree, requestedPath);
    if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
      throw new IllegalArgumentException("Read path is not a regular file");
    }
    byte[] bytes = readBounded(file);
    if (bytes.length > maxOutputBytes) {
      throw new IllegalArgumentException("File exceeds tool output limit");
    }
    String content = decodeText(bytes, "File is not valid UTF-8 text");
    return new ReadFileResult(relative(worktree, file), content, sha256(bytes), bytes.length);
  }

  public SearchFilesResult search(
      Path worktree, String requestedPath, String query, int requestedMaxResults) {
    if (query == null || query.isEmpty()) throw new IllegalArgumentException("query is required");
    if (query.length() > MAX_QUERY_LENGTH) throw new IllegalArgumentException("query is too long");
    int maxResults = Math.min(requestedMaxResults, configuredMaxSearchResults);
    if (maxResults < 1) throw new IllegalArgumentException("maxResults must be positive");
    Path base = resolve(worktree, requestedPath == null ? "." : requestedPath);
    List<SearchMatch> matches = new ArrayList<>();
    int retainedBytes = 0;
    boolean truncated = false;
    try (Stream<Path> stream = Files.walk(base, MAX_DEPTH)) {
      List<Path> files = stream
          .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
          .filter(path -> !isGitMetadata(worktree, path))
          .limit(MAX_VISITED_PATHS + 1L)
          .sorted(Comparator.comparing(path -> relative(worktree, path)))
          .toList();
      if (files.size() > MAX_VISITED_PATHS) truncated = true;
      outer:
      for (Path file : files.stream().limit(MAX_VISITED_PATHS).toList()) {
        if (Files.size(file) > maxFileBytes) continue;
        String content;
        try {
          content = decodeText(readBounded(file), "");
        } catch (IllegalArgumentException exception) {
          continue;
        }
        String[] lines = content.split("\\R", -1);
        for (int lineIndex = 0; lineIndex < lines.length; lineIndex++) {
          int column = lines[lineIndex].indexOf(query);
          if (column < 0) continue;
          String preview = lines[lineIndex].length() <= MAX_PREVIEW_CHARACTERS
              ? lines[lineIndex]
              : lines[lineIndex].substring(0, MAX_PREVIEW_CHARACTERS);
          String relative = relative(worktree, file);
          int estimatedBytes = relative.getBytes(StandardCharsets.UTF_8).length
              + preview.getBytes(StandardCharsets.UTF_8).length + 64;
          if (matches.size() >= maxResults || retainedBytes + estimatedBytes > maxOutputBytes) {
            truncated = true;
            break outer;
          }
          matches.add(new SearchMatch(relative, lineIndex + 1, column + 1, preview));
          retainedBytes += estimatedBytes;
        }
      }
    } catch (IOException exception) {
      throw new IllegalStateException("Could not search workspace files", exception);
    }
    return new SearchFilesResult(List.copyOf(matches), truncated);
  }

  public ReplacementPreview previewReplacement(
      Path worktree, String requestedPath, String expectedSha256, String replacementContent) {
    requireSha256(expectedSha256);
    byte[] replacement = replacementBytes(replacementContent);
    ReadFileResult current = read(worktree, requestedPath);
    if (!current.sha256().equals(expectedSha256)) {
      throw new FileConflictException("File changed since it was read");
    }
    String diff = unifiedReplacementDiff(
        current.path(), current.content(), replacementContent, maxOutputBytes);
    return new ReplacementPreview(
        current.path(), expectedSha256, sha256(replacement), diff);
  }

  public ReadFileResult applyReplacement(
      Path worktree, String requestedPath, String expectedSha256, String replacementContent) {
    ReplacementPreview preview =
        previewReplacement(worktree, requestedPath, expectedSha256, replacementContent);
    Path file = resolve(worktree, preview.path());
    byte[] replacement = replacementBytes(replacementContent);
    Path temporary = null;
    try {
      temporary = Files.createTempFile(file.getParent(), ".petri-change-", ".tmp");
      Files.write(temporary, replacement, StandardOpenOption.TRUNCATE_EXISTING);
      copyPermissions(file, temporary);
      if (!read(worktree, preview.path()).sha256().equals(expectedSha256)) {
        throw new FileConflictException("File changed while the approved patch was being applied");
      }
      try {
        Files.move(
            temporary,
            file,
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException exception) {
        throw new IllegalStateException("Workspace filesystem does not support atomic patch application", exception);
      }
      temporary = null;
      return read(worktree, preview.path());
    } catch (IOException exception) {
      throw new IllegalStateException("Could not apply approved file replacement", exception);
    } finally {
      if (temporary != null) {
        try {
          Files.deleteIfExists(temporary);
        } catch (IOException ignored) {
          // Best effort cleanup; the restricted worktree contains no secret material.
        }
      }
    }
  }

  private Path resolve(Path worktree, String requestedPath) {
    if (requestedPath == null || requestedPath.indexOf('\0') >= 0) {
      throw new IllegalArgumentException("Workspace path is invalid");
    }
    Path root;
    try {
      root = worktree.toRealPath();
    } catch (IOException exception) {
      throw new IllegalStateException("Worktree is unavailable", exception);
    }
    Path relativePath = Path.of(requestedPath);
    if (relativePath.isAbsolute()) throw new IllegalArgumentException("Workspace path must be relative");
    Path candidate = root.resolve(relativePath).normalize();
    if (!candidate.startsWith(root)) throw new IllegalArgumentException("Workspace path escapes worktree");
    if (isGitMetadata(root, candidate)) throw new IllegalArgumentException("Git metadata is not readable");
    try {
      Path current = root;
      for (Path segment : root.relativize(candidate)) {
        current = current.resolve(segment);
        if (Files.isSymbolicLink(current)) {
          throw new IllegalArgumentException("Symbolic links are not supported by workspace tools");
        }
      }
      Path real = candidate.toRealPath();
      if (!real.startsWith(root)) throw new IllegalArgumentException("Workspace path resolves outside worktree");
      return real;
    } catch (IOException exception) {
      throw new IllegalArgumentException("Workspace path does not exist", exception);
    }
  }

  private byte[] readBounded(Path file) {
    try {
      long size = Files.size(file);
      if (size > maxFileBytes) throw new IllegalArgumentException("File exceeds tool size limit");
      try (var input = Files.newInputStream(file)) {
        byte[] bytes = input.readNBytes(maxFileBytes + 1);
        if (bytes.length > maxFileBytes) throw new IllegalArgumentException("File exceeds tool size limit");
        return bytes;
      }
    } catch (IOException exception) {
      throw new IllegalStateException("Could not read workspace file", exception);
    }
  }

  private static String decodeText(byte[] bytes, String message) {
    try {
      return StandardCharsets.UTF_8.newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString();
    } catch (CharacterCodingException exception) {
      throw new IllegalArgumentException(message.isEmpty() ? "Not UTF-8 text" : message, exception);
    }
  }

  private static String sha256(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private byte[] replacementBytes(String replacementContent) {
    if (replacementContent == null) throw new IllegalArgumentException("replacementContent is required");
    byte[] bytes = replacementContent.getBytes(StandardCharsets.UTF_8);
    if (bytes.length > maxFileBytes) throw new IllegalArgumentException("Replacement exceeds tool size limit");
    return bytes;
  }

  private static void requireSha256(String value) {
    if (value == null || !value.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("expectedSha256 must be a lowercase SHA-256 hash");
    }
  }

  private static String unifiedReplacementDiff(
      String path, String current, String replacement, int maxOutputBytes) {
    StringBuilder diff = new StringBuilder()
        .append("--- a/").append(path).append('\n')
        .append("+++ b/").append(path).append('\n');
    String[] oldLines = current.split("\\R", -1);
    String[] newLines = replacement.split("\\R", -1);
    diff.append("@@ -1,").append(oldLines.length)
        .append(" +1,").append(newLines.length).append(" @@\n");
    for (String line : oldLines) diff.append('-').append(line).append('\n');
    for (String line : newLines) diff.append('+').append(line).append('\n');
    if (diff.toString().getBytes(StandardCharsets.UTF_8).length > maxOutputBytes) {
      throw new IllegalArgumentException("Generated diff exceeds tool output limit");
    }
    return diff.toString();
  }

  private static void copyPermissions(Path source, Path target) throws IOException {
    if (Files.getFileStore(source).supportsFileAttributeView("posix")) {
      Set<java.nio.file.attribute.PosixFilePermission> permissions =
          Files.getPosixFilePermissions(source, LinkOption.NOFOLLOW_LINKS);
      Files.setPosixFilePermissions(target, permissions);
    }
  }

  private static boolean isGitMetadata(Path worktree, Path candidate) {
    Path relative = worktree.toAbsolutePath().normalize().relativize(candidate.toAbsolutePath().normalize());
    for (Path segment : relative) {
      if (".git".equals(segment.toString())) return true;
    }
    return false;
  }

  private static String relative(Path worktree, Path path) {
    return worktree.toAbsolutePath().normalize().relativize(path.toAbsolutePath().normalize())
        .toString().replace(path.getFileSystem().getSeparator(), "/");
  }

  private static String type(Path path) {
    if (Files.isSymbolicLink(path)) return "SYMLINK";
    if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) return "DIRECTORY";
    if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return "FILE";
    return "OTHER";
  }

  private static long fileSize(Path path) {
    try {
      return Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) ? Files.size(path) : 0;
    } catch (IOException exception) {
      return 0;
    }
  }
}
