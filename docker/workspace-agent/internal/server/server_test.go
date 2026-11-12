package server

import (
	"archive/tar"
	"bufio"
	"bytes"
	"encoding/base64"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"petri.wenisch.tech/workspace-agent/internal/guard"
)

const token = "secret-token"

func setup(t *testing.T) (*httptest.Server, string) {
	t.Helper()
	root, err := guard.RealRoot(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	worktree := filepath.Join(root, "runs", "p", "w")
	if err := os.MkdirAll(worktree, 0o755); err != nil {
		t.Fatal(err)
	}
	srv := httptest.NewServer((&Server{Root: root, Token: token, Version: "test"}).Handler())
	t.Cleanup(srv.Close)
	return srv, worktree
}

func call(t *testing.T, method, url string, body any, authorized bool) (*http.Response, []byte) {
	t.Helper()
	var reader io.Reader
	if body != nil {
		data, _ := json.Marshal(body)
		reader = bytes.NewReader(data)
	}
	req, _ := http.NewRequest(method, url, reader)
	if authorized {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	data, _ := io.ReadAll(resp.Body)
	return resp, data
}

func TestAuthentication(t *testing.T) {
	srv, worktree := setup(t)
	if resp, _ := call(t, "GET", srv.URL+"/v1/healthz", nil, false); resp.StatusCode != 200 {
		t.Errorf("healthz must not need a token, got %d", resp.StatusCode)
	}
	url := srv.URL + "/v1/files/list?root=" + worktree + "&path=."
	if resp, _ := call(t, "GET", url, nil, false); resp.StatusCode != 401 {
		t.Errorf("missing token: %d", resp.StatusCode)
	}
	req, _ := http.NewRequest("GET", url, nil)
	req.Header.Set("Authorization", "Bearer wrong")
	resp, _ := http.DefaultClient.Do(req)
	if resp.StatusCode != 401 {
		t.Errorf("wrong token: %d", resp.StatusCode)
	}
	if resp, _ := call(t, "GET", url, nil, true); resp.StatusCode != 200 {
		t.Errorf("valid token: %d", resp.StatusCode)
	}
}

// The pod proxy of the Kubernetes API server drops Authorization, so the token also travels in its own header.
func TestTokenHeaderForPodProxy(t *testing.T) {
	srv, worktree := setup(t)
	url := srv.URL + "/v1/files/list?root=" + worktree + "&path=."
	for token, want := range map[string]int{token: 200, "wrong": 401, "": 401} {
		req, _ := http.NewRequest("GET", url, nil)
		req.Header.Set(TokenHeader, token)
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		resp.Body.Close()
		if resp.StatusCode != want {
			t.Errorf("token %q: got %d, want %d", token, resp.StatusCode, want)
		}
	}
}

func TestWorktreeMustBeBelowTheRoot(t *testing.T) {
	srv, _ := setup(t)
	outside := t.TempDir()
	resp, _ := call(t, "GET", srv.URL+"/v1/files/list?root="+outside+"&path=.", nil, true)
	if resp.StatusCode != 400 {
		t.Errorf("a worktree outside the root must be refused, got %d", resp.StatusCode)
	}
}

func TestFileWorkflowAndConflictStatus(t *testing.T) {
	srv, worktree := setup(t)
	resp, data := call(t, "POST", srv.URL+"/v1/files/write", map[string]any{
		"root": worktree, "path": "a.txt", "content": "one"}, true)
	if resp.StatusCode != 200 {
		t.Fatalf("create: %d %s", resp.StatusCode, data)
	}
	var created struct{ Sha256 string }
	_ = json.Unmarshal(data, &created)
	resp, data = call(t, "POST", srv.URL+"/v1/files/write", map[string]any{
		"root": worktree, "path": "a.txt", "content": "two", "expectedSha256": strings.Repeat("0", 64)}, true)
	if resp.StatusCode != 409 || !strings.Contains(string(data), `"conflict"`) {
		t.Errorf("stale write: %d %s", resp.StatusCode, data)
	}
	resp, data = call(t, "GET", srv.URL+"/v1/files/read?root="+worktree+"&path=a.txt", nil, true)
	if resp.StatusCode != 200 || !strings.Contains(string(data), `"content":"one"`) {
		t.Errorf("read: %d %s", resp.StatusCode, data)
	}
	resp, _ = call(t, "GET", srv.URL+"/v1/files/read?root="+worktree+"&path=../../x", nil, true)
	if resp.StatusCode != 400 {
		t.Errorf("escape: %d", resp.StatusCode)
	}
	resp, _ = call(t, "GET", srv.URL+"/v1/files/raw?root="+worktree+"&path=missing", nil, true)
	if resp.StatusCode != 404 {
		t.Errorf("raw missing: %d", resp.StatusCode)
	}
	resp, _ = call(t, "POST", srv.URL+"/v1/files/delete", map[string]any{
		"root": worktree, "path": "a.txt", "expectedSha256": created.Sha256}, true)
	if resp.StatusCode != 204 {
		t.Errorf("delete: %d", resp.StatusCode)
	}
}

func TestLayoutEndpointsStayInsideTheRoot(t *testing.T) {
	srv, worktree := setup(t)
	root := filepath.Dir(filepath.Dir(filepath.Dir(worktree)))
	if resp, _ := call(t, "POST", srv.URL+"/v1/mkdir", map[string]any{"path": filepath.Join(root, "mirrors")}, true); resp.StatusCode != 200 {
		t.Errorf("mkdir: %d", resp.StatusCode)
	}
	if resp, _ := call(t, "POST", srv.URL+"/v1/mkdir", map[string]any{"path": "/etc/evil"}, true); resp.StatusCode != 400 {
		t.Errorf("mkdir outside: %d", resp.StatusCode)
	}
	if resp, _ := call(t, "POST", srv.URL+"/v1/rm", map[string]any{"path": worktree}, true); resp.StatusCode != 400 {
		t.Errorf("rm outside the temporary directories must be refused: %d", resp.StatusCode)
	}
	resp, data := call(t, "POST", srv.URL+"/v1/tmpdir", map[string]any{"prefix": "petri-term-"}, true)
	if resp.StatusCode != 200 {
		t.Fatalf("tmpdir: %d %s", resp.StatusCode, data)
	}
	var made struct{ Path string }
	_ = json.Unmarshal(data, &made)
	if !strings.HasPrefix(made.Path, filepath.Join(root, "tmp")) {
		t.Errorf("tmpdir path %s", made.Path)
	}
	if resp, _ := call(t, "POST", srv.URL+"/v1/rm", map[string]any{"path": made.Path}, true); resp.StatusCode != 200 {
		t.Errorf("rm tmp: %d", resp.StatusCode)
	}
	if _, err := os.Stat(made.Path); !os.IsNotExist(err) {
		t.Error("temporary directory should be gone")
	}
	resp, data = call(t, "GET", srv.URL+"/v1/stat?path="+worktree, nil, true)
	if resp.StatusCode != 200 || !strings.Contains(string(data), `"DIRECTORY"`) {
		t.Errorf("stat: %d %s", resp.StatusCode, data)
	}
}

func TestExecStreamsOutputAndExit(t *testing.T) {
	srv, worktree := setup(t)
	resp, data := call(t, "POST", srv.URL+"/v1/exec", map[string]any{
		"cwd": worktree, "argv": []string{"sh", "-c", "echo hello; exit 4"}, "inheritPath": true,
		"timeoutMillis": 10000, "outputLimitBytes": 1000}, true)
	if resp.StatusCode != 200 {
		t.Fatalf("exec: %d %s", resp.StatusCode, data)
	}
	var output strings.Builder
	var exit map[string]any
	scanner := bufio.NewScanner(bytes.NewReader(data))
	for scanner.Scan() {
		var event map[string]any
		_ = json.Unmarshal(scanner.Bytes(), &event)
		switch event["type"] {
		case "output":
			chunk, _ := base64.StdEncoding.DecodeString(event["data"].(string))
			output.Write(chunk)
		case "exit":
			exit = event
		}
	}
	if !strings.Contains(output.String(), "hello") || exit == nil || exit["exitCode"].(float64) != 4 {
		t.Errorf("output=%q exit=%v", output.String(), exit)
	}
	resp, data = call(t, "POST", srv.URL+"/v1/exec", map[string]any{
		"cwd": worktree, "argv": []string{"no-such-command-here"}, "inheritPath": true}, true)
	if resp.StatusCode != 422 {
		t.Errorf("a command that cannot start: %d %s", resp.StatusCode, data)
	}
	resp, _ = call(t, "POST", srv.URL+"/v1/exec", map[string]any{
		"cwd": "/tmp", "argv": []string{"true"}}, true)
	if resp.StatusCode != 400 {
		t.Errorf("exec outside the root must be refused: %d", resp.StatusCode)
	}
}

// Mirror operations (clone, fetch) run from the workspace root itself.
func TestExecMayRunInTheWorkspaceRoot(t *testing.T) {
	srv, worktree := setup(t)
	root := filepath.Dir(filepath.Dir(filepath.Dir(worktree)))
	resp, data := call(t, "POST", srv.URL+"/v1/exec", map[string]any{
		"cwd": root, "argv": []string{"pwd"}, "inheritPath": true, "timeoutMillis": 10000, "outputLimitBytes": 1000}, true)
	if resp.StatusCode != 200 {
		t.Fatalf("exec in root: %d %s", resp.StatusCode, data)
	}
	resp, _ = call(t, "POST", srv.URL+"/v1/exec", map[string]any{
		"cwd": filepath.Dir(root), "argv": []string{"true"}}, true)
	if resp.StatusCode != 400 {
		t.Errorf("exec above the root must be refused: %d", resp.StatusCode)
	}
}

func TestArchiveRefusesOversizedTreesBeforeStreaming(t *testing.T) {
	srv, worktree := setup(t)
	if err := os.WriteFile(filepath.Join(worktree, "big.txt"), []byte("0123456789"), 0o644); err != nil {
		t.Fatal(err)
	}
	if resp, _ := call(t, "GET", srv.URL+"/v1/archive?path="+worktree+"&maxBytes=5", nil, true); resp.StatusCode != 413 {
		t.Errorf("oversized: %d", resp.StatusCode)
	}
	resp, data := call(t, "GET", srv.URL+"/v1/archive?path="+worktree+"&maxBytes=100", nil, true)
	if resp.StatusCode != 200 || len(data) == 0 {
		t.Errorf("archive: %d (%d bytes)", resp.StatusCode, len(data))
	}
}

func authorized(t *testing.T, method, url string, body io.Reader, contentType string) (*http.Response, []byte) {
	t.Helper()
	req, _ := http.NewRequest(method, url, body)
	req.Header.Set(TokenHeader, token)
	if contentType != "" {
		req.Header.Set("Content-Type", contentType)
	}
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	data, _ := io.ReadAll(resp.Body)
	return resp, data
}

func TestCopyDuplicatesATreeIntoScratchSpaceOnly(t *testing.T) {
	srv, worktree := setup(t)
	root := filepath.Dir(filepath.Dir(filepath.Dir(worktree)))
	if err := os.WriteFile(filepath.Join(worktree, "a.txt"), []byte("hi"), 0o644); err != nil {
		t.Fatal(err)
	}
	destination := filepath.Join(root, "tmp", "copy")
	resp, data := call(t, "POST", srv.URL+"/v1/copy", map[string]any{
		"source": worktree, "destination": destination, "skip": []string{".git"}, "maxBytes": 1000, "maxFiles": 10}, true)
	if resp.StatusCode != 200 || !strings.Contains(string(data), `"copied":true`) {
		t.Fatalf("copy: %d %s", resp.StatusCode, data)
	}
	if got, _ := os.ReadFile(filepath.Join(destination, "a.txt")); string(got) != "hi" {
		t.Errorf("copied content = %q", got)
	}
	resp, _ = call(t, "POST", srv.URL+"/v1/copy", map[string]any{
		"source": worktree, "destination": filepath.Join(root, "runs", "elsewhere")}, true)
	if resp.StatusCode != 400 {
		t.Errorf("a destination outside the scratch directories must be refused, got %d", resp.StatusCode)
	}
	resp, data = call(t, "POST", srv.URL+"/v1/copy", map[string]any{
		"source": worktree, "destination": filepath.Join(root, "tmp", "small"), "maxBytes": 1}, true)
	if resp.StatusCode != 200 || !strings.Contains(string(data), `"copied":false`) {
		t.Errorf("an oversized tree must be reported, not copied: %d %s", resp.StatusCode, data)
	}
}

func TestToolchainIsInstalledReportedAndOnThePathOfCommands(t *testing.T) {
	srv, worktree := setup(t)
	root := filepath.Dir(filepath.Dir(filepath.Dir(worktree)))
	dir := filepath.Join(root, "toolchain")

	_, data := authorized(t, "GET", srv.URL+"/v1/toolchain", nil, "")
	if !strings.Contains(string(data), `"present":false`) {
		t.Fatalf("before the install: %s", data)
	}

	var raw bytes.Buffer
	writer := tar.NewWriter(&raw)
	add := func(name, body string, mode int64) {
		_ = writer.WriteHeader(&tar.Header{Name: name, Mode: mode, Size: int64(len(body)), Typeflag: tar.TypeReg})
		_, _ = writer.Write([]byte(body))
	}
	add("bin/petri-hello", "#!/bin/sh\necho from-the-bundle\n", 0o755)
	marker, _ := json.Marshal(map[string]string{"version": "t1", "prefix": dir, "arch": "amd64"})
	add(".petri-toolchain.json", string(marker), 0o644)
	_ = writer.Close()

	resp, data := authorized(t, "POST", srv.URL+"/v1/toolchain", &raw, "application/x-tar")
	if resp.StatusCode != 200 || !strings.Contains(string(data), `"petri-hello"`) {
		t.Fatalf("install: %d %s", resp.StatusCode, data)
	}
	_, data = authorized(t, "GET", srv.URL+"/v1/info", nil, "")
	if !strings.Contains(string(data), `"version":"t1"`) {
		t.Errorf("info does not report the toolchain: %s", data)
	}

	resp, data = call(t, "POST", srv.URL+"/v1/exec", map[string]any{
		"cwd": worktree, "argv": []string{"petri-hello"}, "inheritPath": true, "pathAppend": []string{filepath.Join(dir, "bin")},
		"timeoutMillis": 10000, "outputLimitBytes": 1000}, true)
	if resp.StatusCode != 200 || !strings.Contains(string(data), base64.StdEncoding.EncodeToString([]byte("from-the-bundle\n"))) {
		t.Errorf("a bundled tool must run when its directory is appended to PATH: %d %s", resp.StatusCode, data)
	}

	resp, _ = authorized(t, "POST", srv.URL+"/v1/toolchain", strings.NewReader("not a tar"), "")
	if resp.StatusCode != 400 {
		t.Errorf("garbage must be refused, got %d", resp.StatusCode)
	}
}
