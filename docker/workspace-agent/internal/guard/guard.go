// Package guard confines every path the agent touches. It mirrors the checks of Petri's own
// WorkspaceFileService so that a pod-hosted workspace refuses exactly what a local one refuses.
package guard

import (
	"os"
	"path/filepath"
	"strings"
)

// Kind classifies an Error so the server can map it to an HTTP status.
type Kind string

const (
	Invalid  Kind = "invalid"
	Conflict Kind = "conflict"
	NotFound Kind = "not_found"
	TooLarge Kind = "too_large"
)

// Error is a user-facing failure with a stable kind.
type Error struct {
	Kind Kind
	Msg  string
}

func (e *Error) Error() string { return e.Msg }

// Errf builds an Error.
func Errf(kind Kind, msg string) *Error { return &Error{Kind: kind, Msg: msg} }

// RealRoot returns the absolute, symlink-free form of a worktree directory.
func RealRoot(worktree string) (string, error) {
	abs, err := filepath.Abs(worktree)
	if err != nil {
		return "", Errf(Invalid, "Worktree is unavailable")
	}
	real, err := filepath.EvalSymlinks(abs)
	if err != nil {
		return "", Errf(Invalid, "Worktree is unavailable")
	}
	return real, nil
}

// Under reports whether p is root or lies below it.
func Under(root, p string) bool {
	return p == root || strings.HasPrefix(p, strings.TrimSuffix(root, string(filepath.Separator))+string(filepath.Separator))
}

// Relative returns p relative to root with forward slashes ("." for root itself).
func Relative(root, p string) string {
	rel, err := filepath.Rel(root, p)
	if err != nil {
		return p
	}
	return filepath.ToSlash(rel)
}

// IsGitMetadata is true when any segment of the relative path is ".git".
func IsGitMetadata(rel string) bool {
	for _, segment := range strings.Split(filepath.ToSlash(rel), "/") {
		if segment == ".git" {
			return true
		}
	}
	return false
}

func candidate(root, requested, gitMessage string) (string, *Error) {
	if strings.ContainsRune(requested, 0) {
		return "", Errf(Invalid, "Workspace path is invalid")
	}
	if filepath.IsAbs(requested) {
		return "", Errf(Invalid, "Workspace path must be relative")
	}
	cand := filepath.Clean(filepath.Join(root, requested))
	if !Under(root, cand) {
		return "", Errf(Invalid, "Workspace path escapes worktree")
	}
	if IsGitMetadata(Relative(root, cand)) {
		return "", Errf(Invalid, gitMessage)
	}
	return cand, nil
}

func rejectSymlinks(root, cand string) *Error {
	rel := Relative(root, cand)
	if rel == "." {
		return nil
	}
	current := root
	for _, segment := range strings.Split(rel, "/") {
		current = filepath.Join(current, segment)
		info, err := os.Lstat(current)
		if err == nil && info.Mode()&os.ModeSymlink != 0 {
			return Errf(Invalid, "Symbolic links are not supported by workspace tools")
		}
	}
	return nil
}

// Resolve returns the real path of an existing entry below root.
func Resolve(root, requested string) (string, error) {
	cand, e := candidate(root, requested, "Git metadata is not readable")
	if e != nil {
		return "", e
	}
	if e := rejectSymlinks(root, cand); e != nil {
		return "", e
	}
	real, err := filepath.EvalSymlinks(cand)
	if err != nil {
		return "", Errf(Invalid, "Workspace path does not exist")
	}
	if !Under(root, real) {
		return "", Errf(Invalid, "Workspace path resolves outside worktree")
	}
	return real, nil
}

// ResolveNonExisting returns a path below root that does not exist yet. With createParents the
// missing parent directories are created.
func ResolveNonExisting(root, requested string, createParents bool) (string, error) {
	cand, e := candidate(root, requested, "Git metadata is not writable")
	if e != nil {
		return "", e
	}
	if e := rejectSymlinks(root, cand); e != nil {
		return "", e
	}
	parent := filepath.Dir(cand)
	if cand == root {
		return "", Errf(Invalid, "Workspace path has no parent directory")
	}
	info, err := os.Lstat(parent)
	switch {
	case err == nil:
		if !info.IsDir() {
			return "", Errf(Invalid, "Workspace path parent is not a directory")
		}
		if createParents {
			realParent, err := filepath.EvalSymlinks(parent)
			if err != nil || !Under(root, realParent) {
				return "", Errf(Invalid, "Workspace path resolves outside worktree")
			}
		}
	case createParents:
		if err := os.MkdirAll(parent, 0o755); err != nil {
			return "", Errf(Invalid, "Workspace path parent does not exist")
		}
	default:
		return "", Errf(Invalid, "Workspace path parent does not exist")
	}
	if _, err := os.Lstat(cand); err == nil {
		return "", Errf(Invalid, "File already exists; use create_patch to replace it")
	}
	return cand, nil
}

// SafeJoin joins an archive entry name to base without ever leaving it or crossing a symlink.
func SafeJoin(base, name string) (string, error) {
	if name == "" || strings.ContainsRune(name, 0) || filepath.IsAbs(name) {
		return "", Errf(Invalid, "Archive entry name is invalid")
	}
	target := filepath.Clean(filepath.Join(base, name))
	if !Under(base, target) {
		return "", Errf(Invalid, "Archive entry escapes its destination")
	}
	rel := Relative(base, target)
	current := base
	if rel != "." {
		for _, segment := range strings.Split(rel, "/") {
			current = filepath.Join(current, segment)
			if info, err := os.Lstat(current); err == nil && info.Mode()&os.ModeSymlink != 0 {
				return "", Errf(Invalid, "Archive entry crosses a symbolic link")
			}
		}
	}
	return target, nil
}
