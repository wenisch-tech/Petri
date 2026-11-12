package archive

import (
	"archive/tar"
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

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

func TestRoundTripSkipsDirectoriesAndSymlinks(t *testing.T) {
	src := t.TempDir()
	put(t, src, "pom.xml", "<project/>")
	put(t, src, "src/main/A.java", "class A {}")
	put(t, src, ".git/HEAD", "ref")
	put(t, src, "node_modules/x/i.js", "x")
	if err := os.Symlink("pom.xml", filepath.Join(src, "link")); err != nil {
		t.Fatal(err)
	}
	skip := map[string]bool{".git": true, "node_modules": true}
	plan, err := Measure(src, skip)
	if err != nil || plan.Files != 2 {
		t.Fatalf("plan %+v %v", plan, err)
	}
	var buffer bytes.Buffer
	if err := Write(&buffer, src, skip); err != nil {
		t.Fatal(err)
	}
	dest := t.TempDir()
	if err := Extract(&buffer, dest, 0); err != nil {
		t.Fatal(err)
	}
	for _, rel := range []string{"pom.xml", "src/main/A.java"} {
		if _, err := os.Stat(filepath.Join(dest, rel)); err != nil {
			t.Errorf("%s missing: %v", rel, err)
		}
	}
	for _, rel := range []string{".git", "node_modules", "link"} {
		if _, err := os.Lstat(filepath.Join(dest, rel)); err == nil {
			t.Errorf("%s must not be archived", rel)
		}
	}
}

func TestRoundTripKeepsLongNamesAndModes(t *testing.T) {
	src := t.TempDir()
	long := strings.Repeat("d", 120) + "/" + strings.Repeat("f", 120) + ".txt"
	put(t, src, long, "long")
	put(t, src, "run.sh", "#!/bin/sh\n")
	if err := os.Chmod(filepath.Join(src, "run.sh"), 0o755); err != nil {
		t.Fatal(err)
	}
	var buffer bytes.Buffer
	if err := Write(&buffer, src, nil); err != nil {
		t.Fatal(err)
	}
	dest := t.TempDir()
	if err := Extract(&buffer, dest, 0); err != nil {
		t.Fatal(err)
	}
	if data, err := os.ReadFile(filepath.Join(dest, long)); err != nil || string(data) != "long" {
		t.Errorf("long name: %q %v", data, err)
	}
	info, _ := os.Stat(filepath.Join(dest, "run.sh"))
	if info.Mode().Perm() != 0o755 {
		t.Errorf("mode %v", info.Mode().Perm())
	}
}

func tarWith(t *testing.T, header tar.Header, body string) *bytes.Buffer {
	t.Helper()
	var buffer bytes.Buffer
	writer := tar.NewWriter(&buffer)
	header.Size = int64(len(body))
	if err := writer.WriteHeader(&header); err != nil {
		t.Fatal(err)
	}
	if _, err := writer.Write([]byte(body)); err != nil {
		t.Fatal(err)
	}
	writer.Close()
	return &buffer
}

func TestExtractRefusesHostileEntries(t *testing.T) {
	cases := map[string]tar.Header{
		"traversal": {Name: "../evil", Typeflag: tar.TypeReg, Mode: 0o644},
		"absolute":  {Name: "/etc/evil", Typeflag: tar.TypeReg, Mode: 0o644},
		"symlink":   {Name: "l", Typeflag: tar.TypeSymlink, Linkname: "/etc"},
		"hardlink":  {Name: "h", Typeflag: tar.TypeLink, Linkname: "x"},
	}
	for name, header := range cases {
		dest := t.TempDir()
		if err := Extract(tarWith(t, header, ""), dest, 0); err == nil {
			t.Errorf("%s: expected refusal", name)
		}
	}
}

func TestExtractEnforcesSize(t *testing.T) {
	dest := t.TempDir()
	err := Extract(tarWith(t, tar.Header{Name: "big", Typeflag: tar.TypeReg, Mode: 0o644}, "0123456789"), dest, 5)
	if err == nil {
		t.Error("oversized archive must be refused")
	}
}

func TestCopyKeepsModesSkipsDirectoriesAndRefusesOversizedTrees(t *testing.T) {
	source := t.TempDir()
	for name, content := range map[string]string{"a.txt": "one", "sub/b.sh": "two", "node_modules/x.js": "skip"} {
		path := filepath.Join(source, name)
		if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(path, []byte(content), 0o755); err != nil {
			t.Fatal(err)
		}
	}
	destination := filepath.Join(t.TempDir(), "copy")
	ok, err := Copy(source, destination, map[string]bool{"node_modules": true}, 100, 10)
	if err != nil || !ok {
		t.Fatalf("copy: %v %v", ok, err)
	}
	if info, err := os.Stat(filepath.Join(destination, "sub/b.sh")); err != nil || info.Mode().Perm()&0o100 == 0 {
		t.Errorf("sub/b.sh: %v %v", info, err)
	}
	if _, err := os.Stat(filepath.Join(destination, "node_modules")); err == nil {
		t.Errorf("skipped directory was copied")
	}
	other := filepath.Join(t.TempDir(), "other")
	if ok, _ := Copy(source, other, map[string]bool{"node_modules": true}, 3, 10); ok {
		t.Errorf("a tree above the byte limit must not be copied")
	}
	if _, err := os.Stat(filepath.Join(other, "a.txt")); err == nil {
		t.Errorf("nothing may be copied when the tree is too large")
	}
}
