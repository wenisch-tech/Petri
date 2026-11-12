// Package server exposes the workspace agent's HTTP API.
package server

import (
	"context"
	"crypto/subtle"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"os"
	"os/exec"
	"os/user"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"time"

	"petri.wenisch.tech/workspace-agent/internal/archive"
	"petri.wenisch.tech/workspace-agent/internal/execx"
	"petri.wenisch.tech/workspace-agent/internal/files"
	"petri.wenisch.tech/workspace-agent/internal/guard"
	"petri.wenisch.tech/workspace-agent/internal/toolchain"
)

const (
	maxBody        = 80 << 20
	maxUploadBytes = 2 << 30
	// The toolchain bundle is large: go alone is a few hundred megabytes unpacked.
	maxToolchainDownload = 4 << 30
	maxToolchainUnpacked = 8 << 30
	heartbeatPeriod      = 5 * time.Second
)

// Server serves one workspace root.
type Server struct {
	Root    string
	Token   string
	Version string
}

// Handler returns the routed, authenticated API.
func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /v1/healthz", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, map[string]any{"ok": true})
	})
	mux.HandleFunc("GET /v1/info", s.info)
	mux.HandleFunc("GET /v1/stat", s.stat)
	mux.HandleFunc("POST /v1/mkdir", s.mkdir)
	mux.HandleFunc("GET /v1/empty", s.empty)
	mux.HandleFunc("POST /v1/rm", s.remove)
	mux.HandleFunc("POST /v1/tmpdir", s.tmpdir)

	mux.HandleFunc("GET /v1/files/list", s.list)
	mux.HandleFunc("GET /v1/files/read", s.read)
	mux.HandleFunc("GET /v1/files/raw", s.raw)
	mux.HandleFunc("GET /v1/files/metadata", s.metadata)
	mux.HandleFunc("GET /v1/files/search", s.search)
	mux.HandleFunc("POST /v1/files/write", s.write)
	mux.HandleFunc("POST /v1/files/mkdir", s.filesMkdir)
	mux.HandleFunc("POST /v1/files/move", s.move)
	mux.HandleFunc("POST /v1/files/delete", s.delete)

	mux.HandleFunc("GET /v1/archive", s.archive)
	mux.HandleFunc("POST /v1/upload", s.upload)
	mux.HandleFunc("POST /v1/copy", s.copy)
	mux.HandleFunc("GET /v1/toolchain", s.toolchainInfo)
	mux.HandleFunc("POST /v1/toolchain", s.toolchainInstall)
	mux.HandleFunc("POST /v1/exec", s.exec)
	return s.authenticated(mux)
}

// TokenHeader carries the agent token through the Kubernetes pod proxy. The API server consumes the
// Authorization header to authenticate the caller and does not forward it, so Petri cannot use it.
// Authorization stays accepted for direct connections and tests.
const TokenHeader = "X-Petri-Agent-Token"

func (s *Server) authenticated(next http.Handler) http.Handler {
	bearer := []byte("Bearer " + s.Token)
	token := []byte(s.Token)
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/v1/healthz" {
			viaHeader := subtle.ConstantTimeCompare([]byte(r.Header.Get(TokenHeader)), token) == 1
			viaBearer := subtle.ConstantTimeCompare([]byte(r.Header.Get("Authorization")), bearer) == 1
			if len(s.Token) == 0 || !(viaHeader || viaBearer) {
				writeJSON(w, http.StatusUnauthorized, map[string]any{"error": "Unauthorized", "kind": "unauthorized"})
				return
			}
		}
		next.ServeHTTP(w, r)
	})
}

// ---- helpers ------------------------------------------------------------------------------

func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}

func writeError(w http.ResponseWriter, err error) {
	var failure *guard.Error
	if errors.As(err, &failure) {
		status := http.StatusBadRequest
		switch failure.Kind {
		case guard.Conflict:
			status = http.StatusConflict
		case guard.NotFound:
			status = http.StatusNotFound
		case guard.TooLarge:
			status = http.StatusRequestEntityTooLarge
		}
		writeJSON(w, status, map[string]any{"error": failure.Msg, "kind": string(failure.Kind)})
		return
	}
	writeJSON(w, http.StatusInternalServerError, map[string]any{"error": "Internal error", "kind": "internal"})
}

func decode(w http.ResponseWriter, r *http.Request, target any) bool {
	r.Body = http.MaxBytesReader(w, r.Body, maxBody)
	if err := json.NewDecoder(r.Body).Decode(target); err != nil {
		writeError(w, guard.Errf(guard.Invalid, "Request body is not valid JSON"))
		return false
	}
	return true
}

func intParam(r *http.Request, name string, fallback int) int {
	if value, err := strconv.Atoi(r.URL.Query().Get(name)); err == nil {
		return value
	}
	return fallback
}

func (s *Server) limits(r *http.Request) files.Limits {
	return files.Limits{
		MaxFileBytes:     intParam(r, "maxFileBytes", 0),
		MaxOutputBytes:   intParam(r, "maxOutputBytes", 0),
		MaxSearchResults: intParam(r, "maxSearchResults", 0),
	}
}

// worktree validates that a worktree directory lies below the workspace root.
func (s *Server) worktree(path string) (string, error) {
	real, err := guard.RealRoot(path)
	if err != nil {
		return "", err
	}
	if real == s.Root || !guard.Under(s.Root, real) {
		return "", guard.Errf(guard.Invalid, "Worktree is outside the workspace root")
	}
	return real, nil
}

// execDir validates a command's working directory: the workspace root itself (mirror operations
// run there) or anything below it.
func (s *Server) execDir(path string) (string, error) {
	real, err := guard.RealRoot(path)
	if err != nil {
		return "", err
	}
	if real != s.Root && !guard.Under(s.Root, real) {
		return "", guard.Errf(guard.Invalid, "Working directory is outside the workspace root")
	}
	return real, nil
}

// hostPath validates an absolute path below the workspace root without crossing symlinks.
func (s *Server) hostPath(path string) (string, error) {
	if !filepath.IsAbs(path) {
		return "", guard.Errf(guard.Invalid, "Path must be absolute")
	}
	clean := filepath.Clean(path)
	if !guard.Under(s.Root, clean) {
		return "", guard.Errf(guard.Invalid, "Path is outside the workspace root")
	}
	if clean == s.Root {
		return clean, nil
	}
	return guard.SafeJoin(s.Root, guard.Relative(s.Root, clean))
}

// scratchPath is hostPath restricted to the temporary directories.
func (s *Server) scratchPath(path string) (string, error) {
	clean, err := s.hostPath(path)
	if err != nil {
		return "", err
	}
	for _, name := range []string{"tmp", "worktree-tmp"} {
		if guard.Under(filepath.Join(s.Root, name), clean) {
			return clean, nil
		}
	}
	return "", guard.Errf(guard.Invalid, "Path is not a temporary directory")
}

// ---- layout -------------------------------------------------------------------------------

func (s *Server) info(w http.ResponseWriter, r *http.Request) {
	current, _ := user.Current()
	name, uid, gid := "", os.Getuid(), os.Getgid()
	if current != nil {
		name = current.Username
	}
	gitVersion, gitOK := "", false
	if out, err := exec.Command("git", "--version").Output(); err == nil {
		gitVersion, gitOK = strings.TrimSpace(string(out)), true
	}
	_, shErr := exec.LookPath("sh")
	uname := ""
	if out, err := exec.Command("uname", "-a").Output(); err == nil {
		uname = strings.TrimSpace(string(out))
	}
	hostname, _ := os.Hostname()
	writeJSON(w, http.StatusOK, map[string]any{
		"version": s.Version, "goos": runtime.GOOS, "goarch": runtime.GOARCH, "uname": uname,
		"hostname": hostname, "uid": uid, "gid": gid, "user": name, "root": s.Root,
		"git":       map[string]any{"available": gitOK, "version": gitVersion},
		"sh":        map[string]any{"available": shErr == nil},
		"toolchain": toolchain.Read(s.toolchainDir()),
	})
}

// toolchainDir is where the bundled tools live. It is part of the workspace volume, so a bundle is
// transferred once per volume and survives the pod being stopped. The bundle's binaries are prepared
// for exactly this path.
func (s *Server) toolchainDir() string { return filepath.Join(s.Root, "toolchain") }

func (s *Server) toolchainInfo(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, http.StatusOK, toolchain.Read(s.toolchainDir()))
}

func (s *Server) toolchainInstall(w http.ResponseWriter, r *http.Request) {
	body := http.MaxBytesReader(w, r.Body, maxToolchainDownload)
	info, err := toolchain.Install(body, s.toolchainDir(), maxToolchainUnpacked)
	if err != nil {
		writeError(w, guard.Errf(guard.Invalid, err.Error()))
		return
	}
	writeJSON(w, http.StatusOK, info)
}

// copy duplicates a directory tree into a scratch directory, inside the pod, so that a tool can work
// on a throw-away copy of a worktree without the tree leaving the pod.
func (s *Server) copy(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Source      string   `json:"source"`
		Destination string   `json:"destination"`
		Skip        []string `json:"skip"`
		MaxBytes    int64    `json:"maxBytes"`
		MaxFiles    int      `json:"maxFiles"`
	}
	if !decode(w, r, &req) {
		return
	}
	source, err := s.hostPath(req.Source)
	if err != nil {
		writeError(w, err)
		return
	}
	destination, err := s.scratchPath(req.Destination)
	if err != nil {
		writeError(w, err)
		return
	}
	if err := os.MkdirAll(destination, 0o755); err != nil {
		writeError(w, guard.Errf(guard.Invalid, "Could not create the destination"))
		return
	}
	skip := map[string]bool{}
	for _, name := range req.Skip {
		skip[name] = true
	}
	copied, err := archive.Copy(source, destination, skip, req.MaxBytes, req.MaxFiles)
	if err != nil {
		writeError(w, guard.Errf(guard.Invalid, "Could not copy the directory"))
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"copied": copied})
}

func (s *Server) stat(w http.ResponseWriter, r *http.Request) {
	path, err := s.hostPath(r.URL.Query().Get("path"))
	if err != nil {
		writeError(w, err)
		return
	}
	info, statErr := os.Lstat(path)
	if statErr != nil {
		writeJSON(w, http.StatusOK, map[string]any{"exists": false, "type": "MISSING", "size": 0, "executable": false})
		return
	}
	kind := "OTHER"
	switch {
	case info.Mode()&os.ModeSymlink != 0:
		kind = "SYMLINK"
	case info.IsDir():
		kind = "DIRECTORY"
	case info.Mode().IsRegular():
		kind = "FILE"
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"exists": true, "type": kind, "size": info.Size(),
		"executable": info.Mode().IsRegular() && info.Mode().Perm()&0o111 != 0,
	})
}

func (s *Server) mkdir(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Path string `json:"path"`
	}
	if !decode(w, r, &body) {
		return
	}
	path, err := s.hostPath(body.Path)
	if err != nil {
		writeError(w, err)
		return
	}
	if err := os.MkdirAll(path, 0o755); err != nil {
		writeError(w, guard.Errf(guard.Invalid, "Could not create directory"))
		return
	}
	real, err := filepath.EvalSymlinks(path)
	if err != nil || !guard.Under(s.Root, real) {
		writeError(w, guard.Errf(guard.Invalid, "Workspace path escaped its configured root"))
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"path": real})
}

func (s *Server) empty(w http.ResponseWriter, r *http.Request) {
	path, err := s.hostPath(r.URL.Query().Get("path"))
	if err != nil {
		writeError(w, err)
		return
	}
	children, readErr := os.ReadDir(path)
	if readErr != nil {
		writeError(w, guard.Errf(guard.Invalid, "Directory does not exist"))
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"empty": len(children) == 0})
}

func (s *Server) remove(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Path string `json:"path"`
	}
	if !decode(w, r, &body) {
		return
	}
	path, err := s.scratchPath(body.Path)
	if err != nil {
		writeError(w, err)
		return
	}
	if path == filepath.Join(s.Root, "tmp") || path == filepath.Join(s.Root, "worktree-tmp") {
		writeError(w, guard.Errf(guard.Invalid, "Refusing to remove a temporary root"))
		return
	}
	_ = os.RemoveAll(path)
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

func (s *Server) tmpdir(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Prefix string `json:"prefix"`
	}
	if !decode(w, r, &body) {
		return
	}
	if body.Prefix == "" || strings.ContainsAny(body.Prefix, "/\\\x00") {
		writeError(w, guard.Errf(guard.Invalid, "Invalid temporary directory prefix"))
		return
	}
	parent, err := s.hostPath(filepath.Join(s.Root, "tmp"))
	if err != nil {
		writeError(w, err)
		return
	}
	if err := os.MkdirAll(parent, 0o755); err != nil {
		writeError(w, guard.Errf(guard.Invalid, "Workspace root is not writable by this user; set fsGroup or runAsUser"))
		return
	}
	dir, err := os.MkdirTemp(parent, body.Prefix)
	if err != nil {
		writeError(w, guard.Errf(guard.Invalid, "Could not create temporary directory"))
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"path": dir})
}

// ---- files --------------------------------------------------------------------------------

func (s *Server) list(w http.ResponseWriter, r *http.Request) {
	root, err := s.worktree(r.URL.Query().Get("root"))
	if err != nil {
		writeError(w, err)
		return
	}
	result, err := files.List(root, r.URL.Query().Get("path"), intParam(r, "maxDepth", 1), s.limits(r))
	respond(w, result, err)
}

func (s *Server) read(w http.ResponseWriter, r *http.Request) {
	root, err := s.worktree(r.URL.Query().Get("root"))
	if err != nil {
		writeError(w, err)
		return
	}
	result, err := files.Read(root, r.URL.Query().Get("path"), s.limits(r))
	respond(w, result, err)
}

func (s *Server) raw(w http.ResponseWriter, r *http.Request) {
	root, err := s.worktree(r.URL.Query().Get("root"))
	if err != nil {
		writeError(w, err)
		return
	}
	maxBytes := int64(intParam(r, "maxBytes", 1_000_000))
	data, found, tooLarge, err := files.Raw(root, r.URL.Query().Get("path"), maxBytes)
	switch {
	case err != nil:
		writeError(w, err)
	case tooLarge:
		writeError(w, guard.Errf(guard.TooLarge, "File is too large"))
	case !found:
		writeError(w, guard.Errf(guard.NotFound, "No such regular file"))
	default:
		w.Header().Set("Content-Type", "application/octet-stream")
		_, _ = w.Write(data)
	}
}

func (s *Server) metadata(w http.ResponseWriter, r *http.Request) {
	root, err := s.worktree(r.URL.Query().Get("root"))
	if err != nil {
		writeError(w, err)
		return
	}
	result, err := files.Metadata(root, r.URL.Query().Get("path"))
	respond(w, result, err)
}

func (s *Server) search(w http.ResponseWriter, r *http.Request) {
	root, err := s.worktree(r.URL.Query().Get("root"))
	if err != nil {
		writeError(w, err)
		return
	}
	result, err := files.Search(root, r.URL.Query().Get("path"), r.URL.Query().Get("query"),
		intParam(r, "maxResults", 50), s.limits(r))
	respond(w, result, err)
}

func (s *Server) write(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Root string `json:"root"`
		files.WriteRequest
		files.Limits
	}
	if !decode(w, r, &body) {
		return
	}
	root, err := s.worktree(body.Root)
	if err != nil {
		writeError(w, err)
		return
	}
	result, err := files.Write(root, body.WriteRequest, body.Limits)
	respond(w, result, err)
}

func (s *Server) filesMkdir(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Root string `json:"root"`
		Path string `json:"path"`
	}
	if !decode(w, r, &body) {
		return
	}
	root, err := s.worktree(body.Root)
	if err != nil {
		writeError(w, err)
		return
	}
	result, err := files.Mkdir(root, body.Path)
	respond(w, result, err)
}

func (s *Server) move(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Root           string `json:"root"`
		Source         string `json:"source"`
		Target         string `json:"target"`
		ExpectedSha256 string `json:"expectedSha256"`
	}
	if !decode(w, r, &body) {
		return
	}
	root, err := s.worktree(body.Root)
	if err != nil {
		writeError(w, err)
		return
	}
	result, err := files.Move(root, body.Source, body.Target, body.ExpectedSha256)
	respond(w, result, err)
}

func (s *Server) delete(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Root           string `json:"root"`
		Path           string `json:"path"`
		ExpectedSha256 string `json:"expectedSha256"`
		Recursive      bool   `json:"recursive"`
	}
	if !decode(w, r, &body) {
		return
	}
	root, err := s.worktree(body.Root)
	if err != nil {
		writeError(w, err)
		return
	}
	if err := files.Delete(root, body.Path, body.ExpectedSha256, body.Recursive); err != nil {
		writeError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

func respond(w http.ResponseWriter, value any, err error) {
	if err != nil {
		writeError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, value)
}

// ---- archives -----------------------------------------------------------------------------

func (s *Server) archive(w http.ResponseWriter, r *http.Request) {
	path, err := s.hostPath(r.URL.Query().Get("path"))
	if err != nil {
		writeError(w, err)
		return
	}
	skip := map[string]bool{}
	for _, name := range strings.Split(r.URL.Query().Get("skip"), ",") {
		if name != "" {
			skip[name] = true
		}
	}
	plan, err := archive.Measure(path, skip)
	if err != nil {
		writeError(w, guard.Errf(guard.Invalid, "Could not inspect the directory"))
		return
	}
	maxBytes := int64(intParam(r, "maxBytes", 0))
	maxFiles := intParam(r, "maxFiles", 0)
	if (maxBytes > 0 && plan.Bytes > maxBytes) || (maxFiles > 0 && plan.Files > maxFiles) {
		writeError(w, guard.Errf(guard.TooLarge, "The directory is too large to archive"))
		return
	}
	w.Header().Set("Content-Type", "application/x-tar")
	w.WriteHeader(http.StatusOK)
	_ = archive.Write(w, path, skip)
}

func (s *Server) upload(w http.ResponseWriter, r *http.Request) {
	path, err := s.scratchPath(r.URL.Query().Get("path"))
	if err != nil {
		writeError(w, err)
		return
	}
	if err := os.MkdirAll(path, 0o755); err != nil {
		writeError(w, guard.Errf(guard.Invalid, "Could not create the destination"))
		return
	}
	body := http.MaxBytesReader(w, r.Body, maxUploadBytes)
	if err := archive.Extract(body, path, maxUploadBytes); err != nil {
		writeError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"ok": true})
}

// ---- exec ---------------------------------------------------------------------------------

type stream struct {
	mutex   sync.Mutex
	w       http.ResponseWriter
	flusher http.Flusher
	started bool
}

func (st *stream) begin() {
	if st.started {
		return
	}
	st.started = true
	st.w.Header().Set("Content-Type", "application/x-ndjson")
	st.w.Header().Set("Cache-Control", "no-cache")
	st.w.Header().Set("X-Accel-Buffering", "no")
	st.w.WriteHeader(http.StatusOK)
}

func (st *stream) send(event map[string]any) {
	st.mutex.Lock()
	defer st.mutex.Unlock()
	st.begin()
	line, _ := json.Marshal(event)
	_, _ = st.w.Write(append(line, '\n'))
	if st.flusher != nil {
		st.flusher.Flush()
	}
}

func (s *Server) exec(w http.ResponseWriter, r *http.Request) {
	var req execx.Request
	if !decode(w, r, &req) {
		return
	}
	cwd, err := s.execDir(req.Cwd)
	if err != nil {
		writeError(w, err)
		return
	}
	req.Cwd = cwd
	flusher, _ := w.(http.Flusher)
	out := &stream{w: w, flusher: flusher}

	ctx, cancel := context.WithCancel(r.Context())
	defer cancel()
	done := make(chan struct{})
	go func() {
		ticker := time.NewTicker(heartbeatPeriod)
		defer ticker.Stop()
		for {
			select {
			case <-ticker.C:
				out.send(map[string]any{"type": "heartbeat"})
			case <-done:
				return
			}
		}
	}()

	result, runErr := execx.Run(ctx, req, func(chunk []byte) {
		out.send(map[string]any{"type": "output", "data": base64.StdEncoding.EncodeToString(chunk)})
	})
	close(done)
	if runErr != nil {
		out.mutex.Lock()
		started := out.started
		out.mutex.Unlock()
		if !started {
			writeJSON(w, http.StatusUnprocessableEntity,
				map[string]any{"error": fmt.Sprintf("Could not start command: %v", runErr), "kind": "exec_failed"})
			return
		}
	}
	out.send(map[string]any{"type": "exit", "exitCode": result.ExitCode,
		"timedOut": result.TimedOut, "truncated": result.Truncated})
}
