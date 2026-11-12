//go:build unix

package execx

import (
	"context"
	"strings"
	"testing"
	"time"
)

func run(t *testing.T, req Request) (string, Result) {
	t.Helper()
	var out strings.Builder
	result, err := Run(context.Background(), req, func(b []byte) { out.Write(b) })
	if err != nil {
		t.Fatal(err)
	}
	return out.String(), result
}

func TestOutputAndExitCode(t *testing.T) {
	out, result := run(t, Request{Cwd: t.TempDir(), Argv: []string{"sh", "-c", "echo out; echo err >&2; exit 3"},
		InheritPath: true})
	if result.ExitCode != 3 || !strings.Contains(out, "out") || !strings.Contains(out, "err") {
		t.Errorf("out=%q result=%+v", out, result)
	}
}

func TestEnvironmentIsExplicit(t *testing.T) {
	t.Setenv("PETRI_LEAK_CHECK", "leaked")
	out, _ := run(t, Request{Cwd: t.TempDir(), Argv: []string{"sh", "-c", "echo \"[$PETRI_LEAK_CHECK][$FOO]\""},
		Env: map[string]string{"FOO": "bar", "PATH": "/usr/bin:/bin"}})
	if strings.TrimSpace(out) != "[][bar]" {
		t.Errorf("environment leaked or missing: %q", out)
	}
}

func TestTimeoutKillsTheWholeProcessGroup(t *testing.T) {
	start := time.Now()
	_, result := run(t, Request{Cwd: t.TempDir(), TimeoutMillis: 300,
		Argv: []string{"sh", "-c", "sleep 30 & sleep 30; wait"}, InheritPath: true})
	if !result.TimedOut || result.ExitCode != -1 {
		t.Errorf("expected timeout, got %+v", result)
	}
	if time.Since(start) > 5*time.Second {
		t.Errorf("process group was not killed promptly: %v", time.Since(start))
	}
}

func TestOutputLimit(t *testing.T) {
	out, result := run(t, Request{Cwd: t.TempDir(), OutputLimitBytes: 10,
		Argv: []string{"sh", "-c", "yes x | head -c 1000"}, InheritPath: true})
	if !result.Truncated || len(out) != 10 {
		t.Errorf("limit: len=%d result=%+v", len(out), result)
	}
}

func TestContextCancellationKills(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	go func() { time.Sleep(200 * time.Millisecond); cancel() }()
	start := time.Now()
	_, err := Run(ctx, Request{Cwd: t.TempDir(), Argv: []string{"sleep", "30"}, InheritPath: true}, func([]byte) {})
	if err != nil {
		t.Logf("run returned %v", err)
	}
	if time.Since(start) > 5*time.Second {
		t.Error("cancellation did not stop the command")
	}
}

func TestMissingCommandFailsToStart(t *testing.T) {
	_, err := Run(context.Background(), Request{Cwd: t.TempDir(), Argv: []string{"definitely-not-a-command"},
		InheritPath: true}, func([]byte) {})
	if err == nil {
		t.Error("starting a missing command must fail")
	}
}

func TestPathDirectoriesAreAddedWhenTheyExist(t *testing.T) {
	existing := t.TempDir()
	env := Environment(Request{
		Env:         map[string]string{"PATH": "/usr/bin"},
		PathPrepend: []string{existing, "relative", "/does/not/exist"},
		PathAppend:  []string{existing + "/missing", "/tmp"},
	})
	var path string
	for _, entry := range env {
		if len(entry) > 5 && entry[:5] == "PATH=" {
			path = entry[5:]
		}
	}
	if want := existing + ":/usr/bin:/tmp"; path != want {
		t.Errorf("PATH = %q, want %q", path, want)
	}
}
