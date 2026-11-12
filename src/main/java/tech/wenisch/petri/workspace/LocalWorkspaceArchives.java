package tech.wenisch.petri.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Set;
import org.springframework.stereotype.Component;

/** On a local host, "downloading" and "uploading" are plain copies on the same filesystem. */
@Component
public class LocalWorkspaceArchives implements WorkspaceArchives {
  private final WorkspaceFiles files;

  public LocalWorkspaceArchives(WorkspaceFiles files) {
    this.files = files;
  }

  @Override
  public boolean downloadTree(Path hostDirectory, Path localDestination, Set<String> skipDirectories,
      long maxBytes, int maxFiles) throws IOException {
    return files.copyTree(hostDirectory, localDestination, skipDirectories, maxBytes, maxFiles);
  }

  @Override
  public void uploadTree(Path localDirectory, Path hostDestination) throws IOException {
    try (var walk = Files.walk(localDirectory)) {
      for (Path source : (Iterable<Path>) walk::iterator) {
        Path target = hostDestination.resolve(localDirectory.relativize(source).toString());
        if (Files.isDirectory(source)) Files.createDirectories(target);
        else Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
      }
    }
  }
}
