package tech.wenisch.petri.project;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataIntegrityViolationException;
import tech.wenisch.petri.git.provider.GitAccountConnectionService;

@Service
public class ProjectImportService {
  private final ProjectImportItemService items;
  private final GitAccountConnectionService connections;

  public ProjectImportService(
      ProjectImportItemService items, GitAccountConnectionService connections) {
    this.items = items;
    this.connections = connections;
  }

  public List<ProjectImportResult> importRepositories(
      UUID connectionId, List<String> repositoryIds, String actorEmail) {
    connections.requireOwned(connectionId, actorEmail);
    if (repositoryIds == null || repositoryIds.isEmpty()) {
      throw new IllegalArgumentException("Select at least one repository");
    }
    LinkedHashSet<String> unique = new LinkedHashSet<>(repositoryIds);
    if (unique.size() > 100) throw new IllegalArgumentException("At most 100 repositories can be imported at once");
    List<ProjectImportResult> results = new ArrayList<>();
    for (String id : unique) {
      String normalized = id == null ? "" : id.trim();
      if (normalized.isBlank() || normalized.length() > 255) {
        results.add(new ProjectImportResult(normalized, null,
            ProjectImportResult.Status.FAILED, null, "Invalid repository id"));
        continue;
      }
      try {
        results.add(items.importOne(connectionId, normalized, actorEmail));
      } catch (DataIntegrityViolationException exception) {
        results.add(new ProjectImportResult(normalized, null,
            ProjectImportResult.Status.ALREADY_EXISTS, null, "Repository is already imported"));
      } catch (RuntimeException exception) {
        results.add(new ProjectImportResult(normalized, null,
            ProjectImportResult.Status.FAILED, null, safeMessage(exception)));
      }
    }
    return results;
  }

  private static String safeMessage(RuntimeException exception) {
    String message = exception.getMessage();
    if (message == null || message.isBlank()) return "Repository import failed";
    return message.substring(0, Math.min(500, message.length()));
  }
}
