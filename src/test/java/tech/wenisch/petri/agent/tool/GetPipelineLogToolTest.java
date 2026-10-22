package tech.wenisch.petri.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tech.wenisch.petri.git.ProjectWorktreeService;
import tech.wenisch.petri.git.provider.PipelineJob;
import tech.wenisch.petri.git.provider.PipelineLog;
import tech.wenisch.petri.git.provider.PipelineRun;
import tech.wenisch.petri.git.provider.ProjectRepositoryActivityService;
import tech.wenisch.petri.git.provider.ProjectRepositoryActivityService.PipelineRunPage;

class GetPipelineLogToolTest {
  private final UUID projectId = UUID.randomUUID();
  private final ProjectWorktreeService worktrees = mock(ProjectWorktreeService.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
  private final ProjectRepositoryActivityService activity = mock(ProjectRepositoryActivityService.class);
  private final GetPipelineLogTool tool = new GetPipelineLogTool(worktrees, activity);
  private final ToolExecutionContext context = new ToolExecutionContext(UUID.randomUUID());

  private PipelineRunPage page(String log) {
    var job = new PipelineJob("12", "test", "FAILED", null, null, null);
    return new PipelineRunPage(null,
        new PipelineRun("7", "CI", "FAILED", "failure", "main", "abc", "push", null, null, null),
        List.of(job), job, new PipelineLog(log, false), null, null);
  }

  @Test
  void returnsTheJobsAndTheTailOfTheLogWithoutColourCodes() {
    when(worktrees.get(context.worktreeId()).projectId()).thenReturn(projectId);
    String log = "first\n\u001B[31mERROR\u001B[0m: boom\nlast\n";
    when(activity.loadPipelineRun(eq(projectId), eq("7"), any())).thenReturn(page(log));

    @SuppressWarnings("unchecked")
    Map<String, Object> result = (Map<String, Object>) tool.execute(context,
        Map.of("runId", "7", "tailLines", 3));

    assertThat((String) result.get("log")).contains("ERROR: boom").doesNotContain("\u001B");
    assertThat(result.get("logTruncated")).isEqualTo(false);
    assertThat((List<?>) result.get("jobs")).hasSize(1);
  }

  @Test
  void marksLogsThatWereCutToTheRequestedTail() {
    when(worktrees.get(context.worktreeId()).projectId()).thenReturn(projectId);
    when(activity.loadPipelineRun(eq(projectId), eq("7"), any())).thenReturn(page("a\nb\nc\nd\n"));

    @SuppressWarnings("unchecked")
    Map<String, Object> result = (Map<String, Object>) tool.execute(context,
        Map.of("runId", "7", "tailLines", 2));

    assertThat(result.get("logTruncated")).isEqualTo(true);
    assertThat((String) result.get("log")).doesNotContain("a");
  }

  @Test
  void refusesNonNumericIdsAndReportsProviderProblems() {
    when(worktrees.get(context.worktreeId()).projectId()).thenReturn(projectId);

    assertThatThrownBy(() -> tool.execute(context, Map.of("runId", "../1")))
        .isInstanceOf(IllegalArgumentException.class);

    when(activity.loadPipelineRun(eq(projectId), eq("7"), any())).thenReturn(
        new PipelineRunPage(null, null, List.of(), null, null, null, "no access"));
    assertThatThrownBy(() -> tool.execute(context, Map.of("runId", "7")))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("no access");
  }
}
