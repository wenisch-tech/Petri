package guard

import (
	"os"
	"path/filepath"
	"testing"
)

func setup(t *testing.T) string {
	t.Helper()
	root, err := RealRoot(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	return root
}

func kindOf(err error) Kind {
	if e, ok := err.(*Error); ok {
		return e.Kind
	}
	return ""
}

func TestResolveRefusesEscapes(t *testing.T) {
	root := setup(t)
	if err := os.WriteFile(filepath.Join(root, "a.txt"), []byte("a"), 0o644); err != nil {
		t.Fatal(err)
	}
	outside := t.TempDir()
	if err := os.Symlink(outside, filepath.Join(root, "link")); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(filepath.Join(root, ".git"), 0o755); err != nil {
		t.Fatal(err)
	}
	cases := map[string]string{
		"../x":        "escapes",
		"/etc/passwd": "must be relative",
		"link":        "Symbolic links",
		"link/x":      "Symbolic links",
		".git":        "Git metadata",
		"sub/.git/x":  "Git metadata",
		"missing.txt": "does not exist",
		"a\x00b":      "invalid",
	}
	for requested, want := range cases {
		_, err := Resolve(root, requested)
		if err == nil {
			t.Errorf("%q: expected refusal", requested)
			continue
		}
		if kindOf(err) != Invalid {
			t.Errorf("%q: kind %v", requested, kindOf(err))
		}
		if !contains(err.Error(), want) {
			t.Errorf("%q: message %q does not mention %q", requested, err.Error(), want)
		}
	}
	if got, err := Resolve(root, "a.txt"); err != nil || got != filepath.Join(root, "a.txt") {
		t.Errorf("a.txt: %v %v", got, err)
	}
	if got, err := Resolve(root, "."); err != nil || got != root {
		t.Errorf(".: %v %v", got, err)
	}
}

func contains(s, sub string) bool {
	for i := 0; i+len(sub) <= len(s); i++ {
		if s[i:i+len(sub)] == sub {
			return true
		}
	}
	return false
}

func TestResolveNonExisting(t *testing.T) {
	root := setup(t)
	if err := os.WriteFile(filepath.Join(root, "exists.txt"), nil, 0o644); err != nil {
		t.Fatal(err)
	}
	if _, err := ResolveNonExisting(root, "exists.txt", false); err == nil {
		t.Error("existing file must be refused")
	}
	if _, err := ResolveNonExisting(root, "no/parent.txt", false); err == nil {
		t.Error("missing parent must be refused without createParents")
	}
	got, err := ResolveNonExisting(root, "new/dir/file.txt", true)
	if err != nil || got != filepath.Join(root, "new/dir/file.txt") {
		t.Fatalf("create parents: %v %v", got, err)
	}
	if _, err := os.Stat(filepath.Join(root, "new/dir")); err != nil {
		t.Errorf("parents were not created: %v", err)
	}
	if _, err := ResolveNonExisting(root, ".git/config", true); err == nil {
		t.Error(".git must be refused")
	}
}

func TestSafeJoin(t *testing.T) {
	base := setup(t)
	if _, err := SafeJoin(base, "../evil"); err == nil {
		t.Error("traversal must be refused")
	}
	if _, err := SafeJoin(base, "/abs"); err == nil {
		t.Error("absolute names must be refused")
	}
	outside := t.TempDir()
	if err := os.Symlink(outside, filepath.Join(base, "l")); err != nil {
		t.Fatal(err)
	}
	if _, err := SafeJoin(base, "l/file"); err == nil {
		t.Error("crossing a symlink must be refused")
	}
	if got, err := SafeJoin(base, "a/b"); err != nil || got != filepath.Join(base, "a/b") {
		t.Errorf("plain join: %v %v", got, err)
	}
}
