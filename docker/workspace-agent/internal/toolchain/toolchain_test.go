package toolchain

import (
	"archive/tar"
	"bytes"
	"compress/gzip"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

type entry struct {
	name, body, link string
	kind             byte
	mode             int64
}

func bundle(t *testing.T, gz bool, entries ...entry) *bytes.Buffer {
	t.Helper()
	var raw bytes.Buffer
	writer := tar.NewWriter(&raw)
	for _, e := range entries {
		header := &tar.Header{Name: e.name, Typeflag: e.kind, Mode: e.mode, Linkname: e.link, Size: int64(len(e.body))}
		if e.kind == 0 {
			header.Typeflag = tar.TypeReg
		}
		if header.Typeflag != tar.TypeReg {
			header.Size = 0
		}
		if err := writer.WriteHeader(header); err != nil {
			t.Fatal(err)
		}
		if header.Typeflag == tar.TypeReg {
			_, _ = writer.Write([]byte(e.body))
		}
	}
	_ = writer.Close()
	if !gz {
		return &raw
	}
	var out bytes.Buffer
	zipper := gzip.NewWriter(&out)
	_, _ = zipper.Write(raw.Bytes())
	_ = zipper.Close()
	return &out
}

func marker(t *testing.T, prefix, version string) entry {
	data, _ := json.Marshal(Marker{Version: version, Prefix: prefix, Arch: "amd64"})
	return entry{name: MarkerFile, body: string(data), mode: 0o644}
}

func target(t *testing.T) string {
	return filepath.Join(t.TempDir(), "toolchain")
}

func TestInstallUnpacksFilesLinksAndModes(t *testing.T) {
	dir := target(t)
	in := bundle(t, true,
		entry{name: "bin/", kind: tar.TypeDir, mode: 0o755},
		entry{name: "usr/bin/git", body: "#!/bin/sh\necho git\n", mode: 0o4755},
		entry{name: "bin/git", kind: tar.TypeSymlink, link: "../usr/bin/git"},
		entry{name: "usr/bin/git-copy", kind: tar.TypeLink, link: "usr/bin/git"},
		marker(t, dir, "v1"))
	info, err := Install(in, dir, 1<<20)
	if err != nil {
		t.Fatal(err)
	}
	if !info.Present || info.Version != "v1" || len(info.Tools) != 1 || info.Tools[0] != "git" {
		t.Fatalf("info = %+v", info)
	}
	stat, _ := os.Stat(filepath.Join(dir, "usr/bin/git"))
	if stat.Mode()&os.ModeSetuid != 0 || stat.Mode().Perm()&0o111 == 0 {
		t.Errorf("mode = %v: setuid must be dropped and execute kept", stat.Mode())
	}
	link, _ := os.Readlink(filepath.Join(dir, "bin/git"))
	if link != "../usr/bin/git" {
		t.Errorf("symlink = %q", link)
	}
	a, _ := os.Stat(filepath.Join(dir, "usr/bin/git"))
	b, _ := os.Stat(filepath.Join(dir, "usr/bin/git-copy"))
	if !os.SameFile(a, b) {
		t.Errorf("the hard link was not preserved")
	}
}

func TestInstallAcceptsAPlainTarAndReplacesAnOlderInstallation(t *testing.T) {
	dir := target(t)
	if _, err := Install(bundle(t, false, entry{name: "old", body: "x", mode: 0o644}, marker(t, dir, "v1")), dir, 1<<20); err != nil {
		t.Fatal(err)
	}
	info, err := Install(bundle(t, false, entry{name: "new", body: "y", mode: 0o644}, marker(t, dir, "v2")), dir, 1<<20)
	if err != nil || info.Version != "v2" {
		t.Fatalf("info=%+v err=%v", info, err)
	}
	if _, err := os.Stat(filepath.Join(dir, "old")); err == nil {
		t.Errorf("the previous installation must be replaced, not merged")
	}
}

func TestInstallRefusesHostileBundles(t *testing.T) {
	cases := map[string][]entry{
		"parent traversal":   {{name: "../escape", body: "x", mode: 0o644}},
		"absolute path":      {{name: "/tmp/escape", body: "x", mode: 0o644}},
		"write below a link": {{name: "evil", kind: tar.TypeSymlink, link: "/tmp"}, {name: "evil/escape", body: "x", mode: 0o644}},
		"hard link outside":  {{name: "x", kind: tar.TypeLink, link: "../../etc/passwd"}},
	}
	for name, entries := range cases {
		dir := target(t)
		if _, err := Install(bundle(t, true, append(entries, marker(t, dir, "v"))...), dir, 1<<20); err == nil {
			t.Errorf("%s: must be refused", name)
		}
		if _, err := os.Stat(dir); err == nil {
			t.Errorf("%s: nothing may be installed after a refused bundle", name)
		}
	}
}

func TestInstallRefusesAWrongPrefixAMissingMarkerAndOversizedBundles(t *testing.T) {
	dir := target(t)
	if _, err := Install(bundle(t, true, marker(t, "/elsewhere/toolchain", "v")), dir, 1<<20); err == nil ||
		!strings.Contains(err.Error(), "/elsewhere/toolchain") {
		t.Errorf("wrong prefix: %v", err)
	}
	if _, err := Install(bundle(t, true, entry{name: "a", body: "x", mode: 0o644}), dir, 1<<20); err == nil {
		t.Errorf("a bundle without a marker must be refused")
	}
	if _, err := Install(bundle(t, true, entry{name: "a", body: strings.Repeat("x", 100), mode: 0o644}, marker(t, dir, "v")), dir, 10); err == nil {
		t.Errorf("an oversized bundle must be refused")
	}
}

func TestAFailedInstallKeepsThePreviousOne(t *testing.T) {
	dir := target(t)
	if _, err := Install(bundle(t, true, marker(t, dir, "good")), dir, 1<<20); err != nil {
		t.Fatal(err)
	}
	if _, err := Install(bundle(t, true, entry{name: "../bad", body: "x", mode: 0o644}), dir, 1<<20); err == nil {
		t.Fatal("expected a refusal")
	}
	if got := Read(dir); got.Version != "good" {
		t.Errorf("the previous installation was lost: %+v", got)
	}
}

func TestReadWithoutAnInstallation(t *testing.T) {
	if info := Read(target(t)); info.Present || info.Tools == nil {
		t.Errorf("info = %+v", info)
	}
}
