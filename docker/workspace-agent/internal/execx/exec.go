//go:build unix

// Package execx runs one process with an explicit environment, a deadline and bounded output,
// reporting output as it arrives. It is the agent's counterpart of Petri's BoundedProcess.
package execx

import (
	"context"
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
	"time"
)

// Request describes one command.
type Request struct {
	Cwd              string            `json:"cwd"`
	Argv             []string          `json:"argv"`
	Env              map[string]string `json:"env"`
	TimeoutMillis    int64             `json:"timeoutMillis"`
	OutputLimitBytes int               `json:"outputLimitBytes"`
	// InheritPath copies PATH and JAVA_HOME of the agent's own environment, so tools of the image
	// resolve without the caller knowing where they live.
	InheritPath bool `json:"inheritPath"`
	// PathPrepend and PathAppend add directories to PATH. Only absolute directories that exist are
	// used, so Petri can name the bundled toolchain without knowing whether it is installed.
	PathPrepend []string `json:"pathPrepend"`
	PathAppend  []string `json:"pathAppend"`
}

// Result is the final state of a command.
type Result struct {
	ExitCode  int
	TimedOut  bool
	Truncated bool
}

// Hard ceilings for a single command.
const (
	MaxTimeout     = time.Hour
	MaxOutputBytes = 64 << 20
)

// Environment builds the complete environment of the child.
func Environment(req Request) []string {
	values := map[string]string{}
	if req.InheritPath {
		for _, name := range []string{"PATH", "JAVA_HOME"} {
			if value, ok := os.LookupEnv(name); ok {
				values[name] = value
			}
		}
	}
	for name, value := range req.Env {
		values[name] = value
	}
	if path := withDirectories(values["PATH"], req.PathPrepend, req.PathAppend); path != "" {
		values["PATH"] = path
	}
	environment := make([]string, 0, len(values))
	for name, value := range values {
		environment = append(environment, name+"="+value)
	}
	return environment
}

// resolve finds a bare command name on the PATH the command will run with. Go would otherwise look it
// up on the agent's own PATH, which knows nothing of the directories Petri adds for a request.
func resolve(name string, environment []string) string {
	if strings.Contains(name, "/") {
		return name
	}
	for _, entry := range environment {
		path, ok := strings.CutPrefix(entry, "PATH=")
		if !ok {
			continue
		}
		for _, directory := range strings.Split(path, ":") {
			if directory == "" {
				continue
			}
			candidate := filepath.Join(directory, name)
			if info, err := os.Stat(candidate); err == nil && info.Mode().IsRegular() && info.Mode().Perm()&0o111 != 0 {
				return candidate
			}
		}
	}
	return name
}

// withDirectories puts the existing directories of before in front of path and those of after behind it.
func withDirectories(path string, before, after []string) string {
	var parts []string
	add := func(directories []string) {
		for _, directory := range directories {
			if !filepath.IsAbs(directory) {
				continue
			}
			if info, err := os.Stat(directory); err == nil && info.IsDir() {
				parts = append(parts, directory)
			}
		}
	}
	add(before)
	if path != "" {
		parts = append(parts, path)
	}
	add(after)
	return strings.Join(parts, ":")
}

// Run starts the command and calls onOutput for every retained chunk. It returns once the process
// (and its output pipe) is finished. The context cancels the whole process group.
func Run(ctx context.Context, req Request, onOutput func([]byte)) (Result, error) {
	if len(req.Argv) == 0 || req.Argv[0] == "" {
		return Result{}, errors.New("a command is required")
	}
	timeout := time.Duration(req.TimeoutMillis) * time.Millisecond
	if timeout <= 0 || timeout > MaxTimeout {
		timeout = MaxTimeout
	}
	limit := req.OutputLimitBytes
	if limit <= 0 || limit > MaxOutputBytes {
		limit = MaxOutputBytes
	}

	environment := Environment(req)
	cmd := exec.Command(resolve(req.Argv[0], environment), req.Argv[1:]...)
	cmd.Dir = req.Cwd
	cmd.Env = environment
	cmd.SysProcAttr = &syscall.SysProcAttr{Setpgid: true}
	reader, writer, err := os.Pipe()
	if err != nil {
		return Result{}, err
	}
	cmd.Stdout = writer
	cmd.Stderr = writer
	if err := cmd.Start(); err != nil {
		reader.Close()
		writer.Close()
		return Result{}, err
	}
	writer.Close()

	killGroup := func() { _ = syscall.Kill(-cmd.Process.Pid, syscall.SIGKILL) }
	timedOut := make(chan struct{})
	finished := make(chan struct{})
	go func() {
		timer := time.NewTimer(timeout)
		defer timer.Stop()
		select {
		case <-timer.C:
			close(timedOut)
			killGroup()
		case <-ctx.Done():
			killGroup()
		case <-finished:
		}
	}()

	truncated := false
	retained := 0
	buffer := make([]byte, 32*1024)
	for {
		n, readErr := reader.Read(buffer)
		if n > 0 {
			room := limit - retained
			if room > 0 {
				take := n
				if take > room {
					take = room
				}
				onOutput(append([]byte(nil), buffer[:take]...))
				retained += take
			}
			if n > room {
				truncated = true
			}
		}
		if readErr != nil {
			// io.EOF, or a pipe closed because the process group was killed: both end the output.
			break
		}
	}
	reader.Close()
	waitErr := cmd.Wait()
	close(finished)

	result := Result{Truncated: truncated}
	select {
	case <-timedOut:
		result.TimedOut = true
		result.ExitCode = -1
		return result, nil
	default:
	}
	if waitErr != nil {
		var exit *exec.ExitError
		if errors.As(waitErr, &exit) {
			result.ExitCode = exit.ExitCode()
			return result, nil
		}
		return result, waitErr
	}
	return result, nil
}
