package tech.wenisch.petri.web;

import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tech.wenisch.petri.git.provider.GitAccountConnectionService;
import tech.wenisch.petri.git.provider.ProviderRepositoryPage;
import tech.wenisch.petri.project.ProjectImportResult;
import tech.wenisch.petri.project.ProjectImportService;

@RestController
@RequestMapping
public class GitConnectionApiController {
  private final GitAccountConnectionService connections;
  private final ProjectImportService imports;

  public GitConnectionApiController(
      GitAccountConnectionService connections, ProjectImportService imports) {
    this.connections = connections;
    this.imports = imports;
  }

  @GetMapping("/git-connections/{id}/repositories")
  ProviderRepositoryPage repositories(
      @PathVariable UUID id,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "50") int pageSize,
      @RequestParam(required = false) String query,
      Principal principal) {
    return connections.repositories(id, page, pageSize, query, principal.getName());
  }

  @PostMapping("/projects/import")
  List<ProjectImportResult> importRepositories(
      @RequestBody ImportProjectsRequest request, Principal principal) {
    return imports.importRepositories(request.connectionId(), request.repositoryIds(), principal.getName());
  }

  @ExceptionHandler(IllegalArgumentException.class)
  ResponseEntity<ProblemDetail> badRequest(IllegalArgumentException exception) {
    return problem(HttpStatus.BAD_REQUEST, exception.getMessage());
  }

  @ExceptionHandler(IllegalStateException.class)
  ResponseEntity<ProblemDetail> conflict(IllegalStateException exception) {
    return problem(HttpStatus.CONFLICT, exception.getMessage());
  }

  private static ResponseEntity<ProblemDetail> problem(HttpStatus status, String message) {
    return ResponseEntity.status(status).body(ProblemDetail.forStatusAndDetail(status, message));
  }

  public record ImportProjectsRequest(UUID connectionId, List<String> repositoryIds) {}
}
