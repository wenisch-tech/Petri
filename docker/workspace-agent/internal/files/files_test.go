package files

import (
	"os"
	"path/filepath"
	"strings"
	"testing"

	"petri.wenisch.tech/workspace-agent/internal/guard"
)

func newRoot(t *testing.T) string {
	t.Helper()
	root, err := guard.RealRoot(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	return root
}

func put(t *testing.T, root, rel, content string) {
	t.Helper()
	path := filepath.Join(root, rel)
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
}

func str(s string) *string { return &s }

func kind(err error) guard.Kind {
	if e, ok := err.(*guard.Error); ok {
		return e.Kind
	}
	return ""
}

func TestListSortsHidesGitAndHonoursDepth(t *testing.T) {
	root := newRoot(t)
	put(t, root, "b.txt", "b")
	put(t, root, "a/deep/c.txt", "c")
	put(t, root, ".git/HEAD", "ref")
	result, err := List(root, ".", 1, Limits{})
	if err != nil {
		t.Fatal(err)
	}
	var paths []string
	for _, entry := range result.Entries {
		paths = append(paths, entry.Path+":"+entry.Type)
	}
	if strings.Join(paths, ",") != "a:DIRECTORY,b.txt:FILE" {
		t.Errorf("unexpected listing %v", paths)
	}
	deeper, err := List(root, ".", 3, Limits{})
	if err != nil {
		t.Fatal(err)
	}
	if len(deeper.Entries) != 4 {
		t.Errorf("depth 3 expected 4 entries, got %v", deeper.Entries)
	}
	if _, err := List(root, ".", 0, Limits{}); err == nil {
		t.Error("depth 0 must be refused")
	}
}

func TestReadChecksTextAndLimits(t *testing.T) {
	root := newRoot(t)
	put(t, root, "ok.txt", "héllo")
	put(t, root, "bin.dat", "\xff\xfe\x00")
	read, err := Read(root, "ok.txt", Limits{})
	if err != nil || read.Content != "héllo" || len(read.Sha256) != 64 {
		t.Fatalf("read: %+v %v", read, err)
	}
	if _, err := Read(root, "bin.dat", Limits{}); err == nil || !strings.Contains(err.Error(), "UTF-8") {
		t.Errorf("binary must be refused, got %v", err)
	}
	if _, err := Read(root, "ok.txt", Limits{MaxFileBytes: 2}); err == nil || !strings.Contains(err.Error(), "size limit") {
		t.Errorf("size limit: %v", err)
	}
	if _, err := Read(root, ".", Limits{}); err == nil {
		t.Error("a directory is not a regular file")
	}
}

func TestWriteCreateAndConditionalReplace(t *testing.T) {
	root := newRoot(t)
	created, err := Write(root, WriteRequest{Path: "sub/new.txt", Content: str("one"), CreateParents: true}, Limits{})
	if err != nil || created.Content != "one" {
		t.Fatalf("create: %+v %v", created, err)
	}
	if _, err := Write(root, WriteRequest{Path: "sub/new.txt", Content: str("two")}, Limits{}); err == nil {
		t.Error("creating over an existing file must fail")
	}
	replaced, err := Write(root, WriteRequest{Path: "sub/new.txt", Content: str("two"), ExpectedSha256: &created.Sha256}, Limits{})
	if err != nil || replaced.Content != "two" {
		t.Fatalf("replace: %+v %v", replaced, err)
	}
	if _, err := Write(root, WriteRequest{Path: "sub/new.txt", Content: str("three"), ExpectedSha256: &created.Sha256}, Limits{}); kind(err) != guard.Conflict {
		t.Errorf("stale hash must conflict, got %v", err)
	}
	bad := "nothex"
	if _, err := Write(root, WriteRequest{Path: "sub/new.txt", Content: str("x"), ExpectedSha256: &bad}, Limits{}); kind(err) != guard.Invalid {
		t.Errorf("malformed hash must be invalid, got %v", err)
	}
	if _, err := Write(root, WriteRequest{Path: "x.txt"}, Limits{}); err == nil {
		t.Error("missing content must be refused")
	}
	if _, err := Write(root, WriteRequest{Path: "big.txt", Content: str("12345")}, Limits{MaxFileBytes: 3}); err == nil {
		t.Error("oversized content must be refused")
	}
	entries, _ := os.ReadDir(filepath.Join(root, "sub"))
	for _, entry := range entries {
		if strings.HasPrefix(entry.Name(), ".petri-change-") {
			t.Errorf("temporary file left behind: %s", entry.Name())
		}
	}
}

func TestWritePreservesPermissions(t *testing.T) {
	root := newRoot(t)
	put(t, root, "run.sh", "#!/bin/sh\n")
	if err := os.Chmod(filepath.Join(root, "run.sh"), 0o755); err != nil {
		t.Fatal(err)
	}
	current, _ := Read(root, "run.sh", Limits{})
	if _, err := Write(root, WriteRequest{Path: "run.sh", Content: str("#!/bin/sh\necho\n"), ExpectedSha256: &current.Sha256}, Limits{}); err != nil {
		t.Fatal(err)
	}
	info, _ := os.Stat(filepath.Join(root, "run.sh"))
	if info.Mode().Perm() != 0o755 {
		t.Errorf("mode changed to %v", info.Mode().Perm())
	}
}

func TestMoveAndDeleteGuards(t *testing.T) {
	root := newRoot(t)
	put(t, root, "a.txt", "a")
	meta, _ := Metadata(root, "a.txt")
	if _, err := Move(root, "a.txt", "b.txt", strings.Repeat("0", 64)); kind(err) != guard.Conflict {
		t.Errorf("stale move must conflict: %v", err)
	}
	moved, err := Move(root, "a.txt", "b.txt", *meta.Sha256)
	if err != nil || moved.Path != "b.txt" {
		t.Fatalf("move: %+v %v", moved, err)
	}
	if _, err := Move(root, ".", "x", ""); err == nil {
		t.Error("root cannot be moved")
	}
	put(t, root, "dir/inner/f.txt", "f")
	if _, err := Move(root, "dir", "dir/inner/dir2", ""); err == nil {
		t.Error("moving into itself must fail")
	}
	if err := Delete(root, "dir", "", false); err == nil || !strings.Contains(err.Error(), "not empty") {
		t.Errorf("non-empty directory needs recursive: %v", err)
	}
	if err := Delete(root, ".", "", true); err == nil {
		t.Error("root cannot be deleted")
	}
	if err := Delete(root, "dir", "", true); err != nil {
		t.Errorf("recursive delete: %v", err)
	}
	if _, err := os.Stat(filepath.Join(root, "dir")); !os.IsNotExist(err) {
		t.Error("directory should be gone")
	}
	if err := Delete(root, "b.txt", strings.Repeat("1", 64), false); kind(err) != guard.Conflict {
		t.Errorf("stale delete must conflict: %v", err)
	}
}

func TestDeleteRefusesSymlinksInSubtree(t *testing.T) {
	root := newRoot(t)
	put(t, root, "d/f.txt", "f")
	if err := os.Symlink("f.txt", filepath.Join(root, "d", "link")); err != nil {
		t.Fatal(err)
	}
	if err := Delete(root, "d", "", true); err == nil {
		t.Fatal("subtree with symlink must be refused")
	}
	if _, err := os.Stat(filepath.Join(root, "d", "f.txt")); err != nil {
		t.Error("nothing may be deleted when refused")
	}
}

func TestSearchFindsLinesAndColumns(t *testing.T) {
	root := newRoot(t)
	put(t, root, "src/a.txt", "one\nsecond needle here\nthree")
	put(t, root, "src/b.txt", "ünï needle")
	put(t, root, ".git/x", "needle")
	put(t, root, "bin.dat", "\xff needle")
	result, err := Search(root, ".", "needle", 10, Limits{})
	if err != nil {
		t.Fatal(err)
	}
	if len(result.Matches) != 2 {
		t.Fatalf("expected 2 matches, got %+v", result.Matches)
	}
	first := result.Matches[0]
	if first.Path != "src/a.txt" || first.Line != 2 || first.Column != 8 {
		t.Errorf("first match %+v", first)
	}
	if result.Matches[1].Column != 5 {
		t.Errorf("column must count UTF-16 units: %+v", result.Matches[1])
	}
	limited, _ := Search(root, ".", "needle", 1, Limits{})
	if len(limited.Matches) != 1 || !limited.Truncated {
		t.Errorf("limit: %+v", limited)
	}
	if _, err := Search(root, ".", "", 5, Limits{}); err == nil {
		t.Error("empty query must be refused")
	}
}

func TestRaw(t *testing.T) {
	root := newRoot(t)
	put(t, root, "f.bin", "abcdef")
	if err := os.Symlink("f.bin", filepath.Join(root, "l")); err != nil {
		t.Fatal(err)
	}
	if data, found, big, _ := Raw(root, "f.bin", 100); !found || big || string(data) != "abcdef" {
		t.Errorf("raw: %q %v %v", data, found, big)
	}
	if _, found, big, _ := Raw(root, "f.bin", 3); !found || !big {
		t.Errorf("too large: %v %v", found, big)
	}
	if _, found, _, _ := Raw(root, "l", 100); found {
		t.Error("symlinks are not served")
	}
	if _, found, _, _ := Raw(root, "../x", 100); found {
		t.Error("escapes are not served")
	}
}

func TestMkdir(t *testing.T) {
	root := newRoot(t)
	made, err := Mkdir(root, "newdir")
	if err != nil || made.Type != "DIRECTORY" {
		t.Fatalf("mkdir: %+v %v", made, err)
	}
	if _, err := Mkdir(root, "newdir"); err == nil {
		t.Error("existing directory must be refused")
	}
	if _, err := Mkdir(root, "a/b"); err == nil {
		t.Error("missing parent must be refused")
	}
}
