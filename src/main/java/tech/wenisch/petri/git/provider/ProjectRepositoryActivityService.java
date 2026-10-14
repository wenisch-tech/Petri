package tech.wenisch.petri.git.provider;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;
import tech.wenisch.petri.project.ProjectAccessService;

/**
 * What is going on in a project's remote repository: pull requests of any state and the latest
 * CI/CD runs, read through the project's connected Git account. Results are cached for a few
 * seconds, so a live-refreshing page does not hammer the provider.
 */
@Service
public class ProjectRepositoryActivityService {
  private static final Duration TTL = Duration.ofSeconds(20);
  private static final int MAX_ENTRIES = 200;

  public record Repository(String provider, String fullName, String webUrl) {}

  /** Either items, or a reason there are none ({@code unavailable} / {@code error}). */
  public record PullRequests(
      Repository repository, String state, List<PullRequestSummary> items, int open, int merged,
      int closed, String unavailable, String error) {}

  public record PullRequestPage(
      Repository repository, PullRequestDetail detail, List<PipelineRun> runs, String unavailable,
      String error) {}

  public record Pipelines(
      Repository repository, List<PipelineRun> items, int running, int failed, int succeeded,
      String unavailable, String error) {}

  private record Cached(long expiresAt, Object value) {}

  private final ProjectAccessService access;
  private final ProjectProviderSourceService sources;
  private final GitAccountConnectionService connections;
  private final GitProviderInstanceRepository instances;
  private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();

  public ProjectRepositoryActivityService(
      ProjectAccessService access, ProjectProviderSourceService sources,
      GitAccountConnectionService connections, GitProviderInstanceRepository instances) {
    this.access = access;
    this.sources = sources;
    this.connections = connections;
    this.instances = instances;
  }

  public PullRequests pullRequests(UUID projectId, String actorEmail, String state) {
    access.requireProject(projectId, actorEmail);
    String wanted = "closed".equals(state) || "all".equals(state) ? state : "open";
    ProjectProviderSource source = sources.find(projectId);
    if (source == null) return new PullRequests(null, wanted, List.of(), 0, 0, 0, NOT_IMPORTED, null);
    Repository repository = repository(source);
    try {
      List<PullRequestSummary> items = cached("pr|" + projectId + "|" + wanted, () -> {
        var call = connections.prepare(source.getConnectionId());
        return call.adapter().listPullRequests(call.instance(), call.token(), call.method(),
            source.getExternalRepositoryId(), source.getFullName(), wanted);
      });
      // The latest pipeline run per branch, for the CI chip of each pull request (best effort).
      java.util.Map<String, String> ciByRef = new java.util.HashMap<>();
      try {
        List<PipelineRun> runs = cached("ci|" + projectId, () -> {
          var call = connections.prepare(source.getConnectionId());
          return call.adapter().listPipelines(call.instance(), call.token(), call.method(),
              source.getExternalRepositoryId(), source.getFullName());
        });
        for (PipelineRun run : runs) {
          if (run.ref() != null) ciByRef.putIfAbsent(run.ref(), run.status());
        }
      } catch (GitProviderException | IllegalStateException ignored) {
        // CI may be unavailable; the list itself still works.
      }
      items = items.stream()
          .map(item -> item.state() == PullRequestSummary.State.OPEN && ciByRef.containsKey(item.headRef())
              ? item.withCi(ciByRef.get(item.headRef())) : item)
          .toList();
      int open = 0;
      int merged = 0;
      int closed = 0;
      for (PullRequestSummary item : items) {
        switch (item.state()) {
          case OPEN -> open++;
          case MERGED -> merged++;
          case CLOSED -> closed++;
        }
      }
      return new PullRequests(repository, wanted, items, open, merged, closed, null, null);
    } catch (GitProviderException | IllegalStateException exception) {
      return new PullRequests(repository, wanted, List.of(), 0, 0, 0, null, explain(exception, "pull requests"));
    }
  }

  public Pipelines pipelines(UUID projectId, String actorEmail) {
    access.requireProject(projectId, actorEmail);
    ProjectProviderSource source = sources.find(projectId);
    if (source == null) return new Pipelines(null, List.of(), 0, 0, 0, NOT_IMPORTED, null);
    Repository repository = repository(source);
    try {
      List<PipelineRun> items = cached("ci|" + projectId, () -> {
        var call = connections.prepare(source.getConnectionId());
        return call.adapter().listPipelines(call.instance(), call.token(), call.method(),
            source.getExternalRepositoryId(), source.getFullName());
      });
      int running = 0;
      int failed = 0;
      int succeeded = 0;
      for (PipelineRun run : items) {
        switch (run.status()) {
          case "RUNNING", "PENDING" -> running++;
          case "FAILED" -> failed++;
          case "SUCCESS" -> succeeded++;
          default -> { }
        }
      }
      items = withPullRequests(projectId, source, items);
      return new Pipelines(repository, items, running, failed, succeeded, null, null);
    } catch (GitProviderException | IllegalStateException exception) {
      return new Pipelines(repository, List.of(), 0, 0, 0, null, explain(exception, "pipelines"));
    }
  }

  /** One pull request with its discussion, changed files and the pipeline runs that belong to it. */
  public PullRequestPage pullRequest(UUID projectId, String actorEmail, long number) {
    access.requireProject(projectId, actorEmail);
    return loadPullRequest(projectId, number);
  }

  /** The same without an access check, for callers that already established the project. */
  public PullRequestPage loadPullRequest(UUID projectId, long number) {
    ProjectProviderSource source = sources.find(projectId);
    if (source == null) return new PullRequestPage(null, null, List.of(), NOT_IMPORTED, null);
    Repository repository = repository(source);
    try {
      PullRequestDetail detail = cached("prd|" + projectId + "|" + number, () -> {
        var call = connections.prepare(source.getConnectionId());
        return call.adapter().pullRequestDetail(call.instance(), call.token(), call.method(),
            source.getExternalRepositoryId(), source.getFullName(), number);
      });
      List<PipelineRun> runs = List.of();
      try {
        List<PipelineRun> all = cached("ci|" + projectId, () -> {
          var call = connections.prepare(source.getConnectionId());
          return call.adapter().listPipelines(call.instance(), call.token(), call.method(),
              source.getExternalRepositoryId(), source.getFullName());
        });
        runs = all.stream().filter(run -> belongsTo(run, detail, number))
            .sorted(java.util.Comparator.comparing(
                (PipelineRun run) -> run.updatedAt() != null ? run.updatedAt() : run.createdAt(),
                java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
            .toList();
      } catch (GitProviderException | IllegalStateException ignored) {
        // CI is optional context.
      }
      return new PullRequestPage(repository, detail, runs, null, null);
    } catch (GitProviderException | IllegalStateException exception) {
      return new PullRequestPage(repository, null, List.of(), null, explain(exception, "this pull request"));
    }
  }

  public record ActionResult(boolean ok, String message) {}

  /** Comments on, reviews, merges or closes a pull request as the project owner's connected account. */
  public ActionResult act(UUID projectId, String actorEmail, long number, String action, String body) {
    access.requireProject(projectId, actorEmail);
    ProjectProviderSource source = sources.find(projectId);
    if (source == null) return new ActionResult(false, NOT_IMPORTED);
    String text = body == null ? "" : body.strip();
    if (text.length() > 20_000) return new ActionResult(false, "The comment is too long (20,000 characters at most).");
    String comment = action == null ? "" : action;
    boolean needsText = comment.equals("comment") || comment.equals("request_changes");
    if (needsText && text.isEmpty()) {
      return new ActionResult(false, comment.equals("comment")
          ? "Write a comment first." : "Say what should change before requesting changes.");
    }
    try {
      var call = connections.prepare(source.getConnectionId());
      GitProviderAdapter adapter = call.adapter();
      String repositoryId = source.getExternalRepositoryId();
      String fullName = source.getFullName();
      String done;
      switch (comment) {
        case "comment" -> {
          adapter.postPullRequestComment(call.instance(), call.token(), call.method(),
              repositoryId, fullName, number, null, text);
          done = "Comment posted.";
        }
        case "approve" -> {
          adapter.reviewPullRequest(call.instance(), call.token(), call.method(),
              repositoryId, fullName, number, true, text);
          done = "Pull request approved.";
        }
        case "request_changes" -> {
          adapter.reviewPullRequest(call.instance(), call.token(), call.method(),
              repositoryId, fullName, number, false, text);
          done = "Changes requested.";
        }
        case "merge" -> {
          adapter.mergePullRequest(call.instance(), call.token(), call.method(),
              repositoryId, fullName, number);
          done = "Pull request merged.";
        }
        case "close" -> {
          adapter.closePullRequest(call.instance(), call.token(), call.method(),
              repositoryId, fullName, number);
          done = "Pull request closed.";
        }
        default -> {
          return new ActionResult(false, "Unknown action.");
        }
      }
      String prefix = projectId.toString();
      cache.keySet().removeIf(key -> key.contains(prefix));
      return new ActionResult(true, done);
    } catch (GitProviderException | IllegalStateException exception) {
      String message = exception.getMessage() == null ? "" : exception.getMessage();
      if (message.contains("HTTP 401") || message.contains("HTTP 403")) {
        message = "The connected account is not allowed to do this. Its token needs write access "
            + "to pull requests (" + message + ").";
      }
      return new ActionResult(false, message.isBlank() ? "The provider rejected the change." : message);
    }
  }

  /** A run belongs to a pull request by its head commit, its branch or its merge-request ref. */
  private static boolean belongsTo(PipelineRun run, PullRequestDetail detail, long number) {
    String ref = run.ref();
    if (run.sha() != null && detail.headSha() != null && run.sha().equals(detail.headSha())) return true;
    if (ref == null) return false;
    return ref.equals(detail.summary().headRef())
        || ref.matches("refs/(pull|merge-requests)/" + number + "/.*");
  }

  /** Marks the runs that belong to a pull request (best effort; the run list stays valid without). */
  private List<PipelineRun> withPullRequests(
      UUID projectId, ProjectProviderSource source, List<PipelineRun> runs) {
    try {
      List<PullRequestSummary> pulls = cached("pr|" + projectId + "|all", () -> {
        var call = connections.prepare(source.getConnectionId());
        return call.adapter().listPullRequests(call.instance(), call.token(), call.method(),
            source.getExternalRepositoryId(), source.getFullName(), "all");
      });
      return runs.stream().map(run -> {
        java.util.regex.Matcher matcher = MERGE_REF.matcher(run.ref() == null ? "" : run.ref());
        if (matcher.matches()) {
          long number = Long.parseLong(matcher.group(2));
          String title = pulls.stream().filter(pr -> pr.number() == number).map(PullRequestSummary::title)
              .findFirst().orElse(null);
          return run.withPullRequest(number, title);
        }
        for (PullRequestSummary pr : pulls) {
          if (run.ref() != null && run.ref().equals(pr.headRef())
              && pr.state() == PullRequestSummary.State.OPEN) {
            return run.withPullRequest(pr.number(), pr.title());
          }
        }
        return run;
      }).toList();
    } catch (GitProviderException | IllegalStateException exception) {
      return runs;
    }
  }

  private static final java.util.regex.Pattern MERGE_REF =
      java.util.regex.Pattern.compile("refs/(pull|merge-requests)/(\\d+)/.*");

  private static final String NOT_IMPORTED =
      "This project was not imported from a connected Git account, so Petri cannot read its "
          + "pull requests or pipelines. Connect an account under Git accounts and import the repository.";

  private Repository repository(ProjectProviderSource source) {
    String provider = instances.findById(source.getProviderInstanceId())
        .map(instance -> instance.getProviderType().name()).orElse(null);
    return new Repository(provider, source.getFullName(), source.getWebUrl());
  }

  @SuppressWarnings("unchecked")
  private <T> T cached(String key, Supplier<T> loader) {
    long now = System.nanoTime();
    Cached hit = cache.get(key);
    if (hit != null && hit.expiresAt() > now) return (T) hit.value();
    T value = loader.get();
    if (cache.size() > MAX_ENTRIES) cache.clear();
    cache.put(key, new Cached(now + TTL.toNanos(), value));
    return value;
  }

  private static String explain(RuntimeException exception, String what) {
    String message = exception.getMessage() == null ? "" : exception.getMessage();
    if (exception instanceof IllegalStateException) {
      return message.isBlank() ? "The Git account connection is not usable." : message;
    }
    if (message.contains("HTTP 401") || message.contains("HTTP 403")) {
      return "The connected account is not allowed to read " + what + " of this repository. "
          + "Replace the token or reconnect the account with the needed scope (" + message + ").";
    }
    if (message.contains("HTTP 404")) {
      return "The provider has no " + what + " for this repository: it may not exist, may be private "
          + "for this account, or CI may be disabled or unsupported on this server.";
    }
    return message.isBlank() ? "The provider could not be reached." : message;
  }
}
