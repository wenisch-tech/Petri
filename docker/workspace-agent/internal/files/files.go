// Package files implements the guarded file operations behind Petri's workspace explorer, editor
// and agent file tools. Behaviour and messages follow WorkspaceFileService.
package files

import (
	"crypto/sha256"
	"encoding/hex"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"unicode/utf16"
	"unicode/utf8"

	"petri.wenisch.tech/workspace-agent/internal/guard"
)

const (
	maxDepth        = 20
	maxQueryLength  = 500
	maxPreviewChars = 500
	maxVisitedPaths = 10_000
)

var sha256Pattern = regexp.MustCompile(`^[0-9a-f]{64}$`)

// Limits are supplied per request so the configuration stays in Petri.
type Limits struct {
	MaxFileBytes     int `json:"maxFileBytes"`
	MaxOutputBytes   int `json:"maxOutputBytes"`
	MaxSearchResults int `json:"maxSearchResults"`
}

// Hard ceilings so a bad request cannot exhaust the pod.
const (
	ceilingFileBytes   = 64 << 20
	ceilingOutputBytes = 64 << 20
)

func (l Limits) clamp() Limits {
	if l.MaxFileBytes <= 0 || l.MaxFileBytes > ceilingFileBytes {
		l.MaxFileBytes = ceilingFileBytes
	}
	if l.MaxOutputBytes <= 0 || l.MaxOutputBytes > ceilingOutputBytes {
		l.MaxOutputBytes = ceilingOutputBytes
	}
	if l.MaxSearchResults <= 0 {
		l.MaxSearchResults = 200
	}
	return l
}

// Entry is one row of a directory listing.
type Entry struct {
	Path string `json:"path"`
	Type string `json:"type"`
	Size int64  `json:"size"`
}

// ListResult is a bounded directory listing.
type ListResult struct {
	Entries   []Entry `json:"entries"`
	Truncated bool    `json:"truncated"`
}

// ReadResult is a UTF-8 text file with its hash.
type ReadResult struct {
	Path    string `json:"path"`
	Content string `json:"content"`
	Sha256  string `json:"sha256"`
	Size    int64  `json:"size"`
}

// Mutation describes an entry after it was created or moved, or its current metadata.
type Mutation struct {
	Path   string  `json:"path"`
	Type   string  `json:"type"`
	Sha256 *string `json:"sha256"`
	Size   int64   `json:"size"`
}

// Match is one search hit.
type Match struct {
	Path    string `json:"path"`
	Line    int    `json:"line"`
	Column  int    `json:"column"`
	Preview string `json:"preview"`
}

// SearchResult is a bounded set of hits.
type SearchResult struct {
	Matches   []Match `json:"matches"`
	Truncated bool    `json:"truncated"`
}

func typeOf(info fs.FileInfo) string {
	switch {
	case info.Mode()&os.ModeSymlink != 0:
		return "SYMLINK"
	case info.IsDir():
		return "DIRECTORY"
	case info.Mode().IsRegular():
		return "FILE"
	default:
		return "OTHER"
	}
}

func sizeOf(info fs.FileInfo) int64 {
	if info.Mode().IsRegular() {
		return info.Size()
	}
	return 0
}

func sum(data []byte) string {
	digest := sha256.Sum256(data)
	return hex.EncodeToString(digest[:])
}

func fileSum(path string) (string, error) {
	file, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer file.Close()
	hash := sha256.New()
	if _, err := io.Copy(hash, file); err != nil {
		return "", err
	}
	return hex.EncodeToString(hash.Sum(nil)), nil
}

func requireSha(value string) error {
	if !sha256Pattern.MatchString(value) {
		return guard.Errf(guard.Invalid, "expectedSha256 must be a lowercase SHA-256 hash")
	}
	return nil
}

// List returns the entries below a directory up to maxDepth levels, sorted by path.
func List(root, requested string, depth int, lim Limits) (*ListResult, error) {
	lim = lim.clamp()
	if depth < 1 || depth > maxDepth {
		return nil, guard.Errf(guard.Invalid, "maxDepth must be between 1 and 20")
	}
	base, err := guard.Resolve(root, requested)
	if err != nil {
		return nil, err
	}
	if info, err := os.Lstat(base); err != nil || !info.IsDir() {
		return nil, guard.Errf(guard.Invalid, "List path is not a directory")
	}
	var candidates []string
	truncated := false
	walkErr := filepath.WalkDir(base, func(path string, entry fs.DirEntry, err error) error {
		if err != nil {
			return nil
		}
		if path == base {
			return nil
		}
		if entry.Name() == ".git" {
			if entry.IsDir() {
				return filepath.SkipDir
			}
			return nil
		}
		relToBase := guard.Relative(base, path)
		if strings.Count(relToBase, "/")+1 > depth {
			if entry.IsDir() {
				return filepath.SkipDir
			}
			return nil
		}
		candidates = append(candidates, path)
		if len(candidates) > maxVisitedPaths {
			truncated = true
			return filepath.SkipAll
		}
		return nil
	})
	if walkErr != nil {
		return nil, walkErr
	}
	sort.Slice(candidates, func(i, j int) bool {
		return guard.Relative(root, candidates[i]) < guard.Relative(root, candidates[j])
	})
	if len(candidates) > maxVisitedPaths {
		candidates = candidates[:maxVisitedPaths]
	}
	result := &ListResult{Entries: []Entry{}}
	retained := 0
	for _, candidate := range candidates {
		rel := guard.Relative(root, candidate)
		estimated := len(rel) + 64
		if retained+estimated > lim.MaxOutputBytes {
			truncated = true
			break
		}
		info, err := os.Lstat(candidate)
		if err != nil {
			continue
		}
		result.Entries = append(result.Entries, Entry{Path: rel, Type: typeOf(info), Size: sizeOf(info)})
		retained += estimated
	}
	result.Truncated = truncated
	return result, nil
}

func readBounded(path string, lim Limits) ([]byte, error) {
	info, err := os.Stat(path)
	if err != nil {
		return nil, guard.Errf(guard.Invalid, "Could not read workspace file")
	}
	if info.Size() > int64(lim.MaxFileBytes) {
		return nil, guard.Errf(guard.Invalid, "File exceeds tool size limit")
	}
	file, err := os.Open(path)
	if err != nil {
		return nil, guard.Errf(guard.Invalid, "Could not read workspace file")
	}
	defer file.Close()
	data, err := io.ReadAll(io.LimitReader(file, int64(lim.MaxFileBytes)+1))
	if err != nil {
		return nil, guard.Errf(guard.Invalid, "Could not read workspace file")
	}
	if len(data) > lim.MaxFileBytes {
		return nil, guard.Errf(guard.Invalid, "File exceeds tool size limit")
	}
	return data, nil
}

// Read returns a UTF-8 text file below the output limit.
func Read(root, requested string, lim Limits) (*ReadResult, error) {
	lim = lim.clamp()
	path, err := guard.Resolve(root, requested)
	if err != nil {
		return nil, err
	}
	if info, err := os.Lstat(path); err != nil || !info.Mode().IsRegular() {
		return nil, guard.Errf(guard.Invalid, "Read path is not a regular file")
	}
	data, err := readBounded(path, lim)
	if err != nil {
		return nil, err
	}
	if len(data) > lim.MaxOutputBytes {
		return nil, guard.Errf(guard.Invalid, "File exceeds tool output limit")
	}
	if !utf8.Valid(data) {
		return nil, guard.Errf(guard.Invalid, "File is not valid UTF-8 text")
	}
	return &ReadResult{Path: guard.Relative(root, path), Content: string(data), Sha256: sum(data), Size: int64(len(data))}, nil
}

// Raw returns the bytes of a regular, non-symlink file. found is false when there is no such file
// and tooLarge when it exceeds maxBytes.
func Raw(root, requested string, maxBytes int64) (data []byte, found bool, tooLarge bool, err error) {
	if strings.ContainsRune(requested, 0) || filepath.IsAbs(requested) {
		return nil, false, false, nil
	}
	cand := filepath.Clean(filepath.Join(root, requested))
	if !guard.Under(root, cand) {
		return nil, false, false, nil
	}
	info, statErr := os.Lstat(cand)
	if statErr != nil || !info.Mode().IsRegular() {
		return nil, false, false, nil
	}
	if info.Size() > maxBytes {
		return nil, true, true, nil
	}
	file, openErr := os.Open(cand)
	if openErr != nil {
		return nil, false, false, nil
	}
	defer file.Close()
	data, readErr := io.ReadAll(io.LimitReader(file, maxBytes+1))
	if readErr != nil {
		return nil, false, false, readErr
	}
	if int64(len(data)) > maxBytes {
		return nil, true, true, nil
	}
	return data, true, false, nil
}

// Metadata describes an entry; files include their hash.
func Metadata(root, requested string) (*Mutation, error) {
	path, err := guard.Resolve(root, requested)
	if err != nil {
		return nil, err
	}
	return entry(root, path)
}

func entry(root, path string) (*Mutation, error) {
	info, err := os.Lstat(path)
	if err != nil {
		return nil, guard.Errf(guard.Invalid, "Workspace path does not exist")
	}
	mutation := &Mutation{Path: guard.Relative(root, path), Type: typeOf(info), Size: sizeOf(info)}
	if mutation.Type == "FILE" {
		hash, err := fileSum(path)
		if err != nil {
			return nil, guard.Errf(guard.Invalid, "Could not hash workspace file")
		}
		mutation.Sha256 = &hash
	}
	return mutation, nil
}

// splitLines splits like Java's String.split("\\R", -1): CRLF, LF, CR, VT, FF, NEL, LS and PS.
func splitLines(content string) []string {
	var lines []string
	var current strings.Builder
	runes := []rune(content)
	for i := 0; i < len(runes); i++ {
		switch runes[i] {
		case '\r':
			if i+1 < len(runes) && runes[i+1] == '\n' {
				i++
			}
			lines = append(lines, current.String())
			current.Reset()
		case '\n', '\u000B', '\u000C', '\u0085', '\u2028', '\u2029':
			lines = append(lines, current.String())
			current.Reset()
		default:
			current.WriteRune(runes[i])
		}
	}
	return append(lines, current.String())
}

func utf16Length(s string) int { return len(utf16.Encode([]rune(s))) }

func truncateUTF16(s string, limit int) string {
	if utf16Length(s) <= limit {
		return s
	}
	units := utf16.Encode([]rune(s))
	return string(utf16.Decode(units[:limit]))
}

// Search finds a literal string in the text files below a path.
func Search(root, requested, query string, requestedMax int, lim Limits) (*SearchResult, error) {
	lim = lim.clamp()
	if query == "" {
		return nil, guard.Errf(guard.Invalid, "query is required")
	}
	if utf16Length(query) > maxQueryLength {
		return nil, guard.Errf(guard.Invalid, "query is too long")
	}
	maxResults := requestedMax
	if lim.MaxSearchResults < maxResults {
		maxResults = lim.MaxSearchResults
	}
	if maxResults < 1 {
		return nil, guard.Errf(guard.Invalid, "maxResults must be positive")
	}
	base, err := guard.Resolve(root, requested)
	if err != nil {
		return nil, err
	}
	var files []string
	truncated := false
	_ = filepath.WalkDir(base, func(path string, entry fs.DirEntry, err error) error {
		if err != nil {
			return nil
		}
		if entry.Name() == ".git" {
			if entry.IsDir() {
				return filepath.SkipDir
			}
			return nil
		}
		if strings.Count(guard.Relative(base, path), "/")+1 > maxDepth && path != base {
			if entry.IsDir() {
				return filepath.SkipDir
			}
			return nil
		}
		if entry.Type().IsRegular() {
			files = append(files, path)
			if len(files) > maxVisitedPaths {
				truncated = true
				return filepath.SkipAll
			}
		}
		return nil
	})
	sort.Slice(files, func(i, j int) bool {
		return guard.Relative(root, files[i]) < guard.Relative(root, files[j])
	})
	if len(files) > maxVisitedPaths {
		files = files[:maxVisitedPaths]
	}
	result := &SearchResult{Matches: []Match{}}
	retained := 0
	for _, file := range files {
		info, err := os.Lstat(file)
		if err != nil || info.Size() > int64(lim.MaxFileBytes) {
			continue
		}
		data, err := readBounded(file, lim)
		if err != nil || !utf8.Valid(data) {
			continue
		}
		rel := guard.Relative(root, file)
		for index, line := range splitLines(string(data)) {
			at := strings.Index(line, query)
			if at < 0 {
				continue
			}
			preview := truncateUTF16(line, maxPreviewChars)
			estimated := len(rel) + len(preview) + 64
			if len(result.Matches) >= maxResults || retained+estimated > lim.MaxOutputBytes {
				result.Truncated = true
				return result, nil
			}
			result.Matches = append(result.Matches, Match{Path: rel, Line: index + 1, Column: utf16Length(line[:at]) + 1, Preview: preview})
			retained += estimated
		}
	}
	result.Truncated = truncated
	return result, nil
}

// WriteRequest creates a file (ExpectedSha256 nil) or conditionally replaces one.
type WriteRequest struct {
	Path           string  `json:"path"`
	Content        *string `json:"content"`
	ExpectedSha256 *string `json:"expectedSha256"`
	// CreateParents creates missing parent directories of a new file; Petri's own tools do not.
	CreateParents bool `json:"createParents"`
}

func writeTemp(dir string, data []byte) (string, error) {
	temp, err := os.CreateTemp(dir, ".petri-change-*.tmp")
	if err != nil {
		return "", guard.Errf(guard.Invalid, "Could not write workspace file")
	}
	name := temp.Name()
	if _, err := temp.Write(data); err != nil {
		temp.Close()
		os.Remove(name)
		return "", guard.Errf(guard.Invalid, "Could not write workspace file")
	}
	if err := temp.Close(); err != nil {
		os.Remove(name)
		return "", guard.Errf(guard.Invalid, "Could not write workspace file")
	}
	return name, nil
}

// Write applies a creation or a conditional replacement atomically.
func Write(root string, req WriteRequest, lim Limits) (*ReadResult, error) {
	lim = lim.clamp()
	if req.Content == nil {
		return nil, guard.Errf(guard.Invalid, "replacementContent is required")
	}
	data := []byte(*req.Content)
	if len(data) > lim.MaxFileBytes {
		return nil, guard.Errf(guard.Invalid, "Replacement exceeds tool size limit")
	}
	if req.ExpectedSha256 == nil {
		return create(root, req.Path, data, req.CreateParents, lim)
	}
	if err := requireSha(*req.ExpectedSha256); err != nil {
		return nil, err
	}
	current, err := Read(root, req.Path, lim)
	if err != nil {
		return nil, err
	}
	if current.Sha256 != *req.ExpectedSha256 {
		return nil, guard.Errf(guard.Conflict, "File changed since it was read")
	}
	file, err := guard.Resolve(root, current.Path)
	if err != nil {
		return nil, err
	}
	temp, err := writeTemp(filepath.Dir(file), data)
	if err != nil {
		return nil, err
	}
	defer os.Remove(temp)
	if info, statErr := os.Stat(file); statErr == nil {
		_ = os.Chmod(temp, info.Mode().Perm())
	}
	again, err := Read(root, current.Path, lim)
	if err != nil {
		return nil, err
	}
	if again.Sha256 != *req.ExpectedSha256 {
		return nil, guard.Errf(guard.Conflict, "File changed while the approved patch was being applied")
	}
	if err := os.Rename(temp, file); err != nil {
		return nil, guard.Errf(guard.Invalid, "Could not apply approved file replacement")
	}
	return Read(root, current.Path, lim)
}

func create(root, requested string, data []byte, createParents bool, lim Limits) (*ReadResult, error) {
	file, err := guard.ResolveNonExisting(root, requested, createParents)
	if err != nil {
		return nil, err
	}
	temp, err := writeTemp(filepath.Dir(file), data)
	if err != nil {
		return nil, err
	}
	defer os.Remove(temp)
	// A hard link fails when the target appeared meanwhile, which makes creation atomic without
	// the race of "check, then rename". Filesystems without hard links fall back to check+rename.
	if linkErr := os.Link(temp, file); linkErr != nil {
		if os.IsExist(linkErr) {
			return nil, guard.Errf(guard.Conflict, "File was created while the approved patch was being applied")
		}
		if _, statErr := os.Lstat(file); statErr == nil {
			return nil, guard.Errf(guard.Conflict, "File was created while the approved patch was being applied")
		}
		if err := os.Rename(temp, file); err != nil {
			return nil, guard.Errf(guard.Invalid, "Could not apply approved file creation")
		}
	}
	rel := guard.Relative(root, file)
	return Read(root, rel, lim)
}

// Mkdir creates one directory whose parent exists.
func Mkdir(root, requested string) (*Mutation, error) {
	dir, err := guard.ResolveNonExisting(root, requested, false)
	if err != nil {
		return nil, err
	}
	if err := os.Mkdir(dir, 0o755); err != nil {
		return nil, guard.Errf(guard.Invalid, "Could not create workspace directory")
	}
	return entry(root, dir)
}

// Move renames an entry atomically; files are guarded by their hash.
func Move(root, source, target, expectedSha string) (*Mutation, error) {
	src, err := guard.Resolve(root, source)
	if err != nil {
		return nil, err
	}
	if src == root {
		return nil, guard.Errf(guard.Invalid, "Workspace root cannot be moved")
	}
	if info, statErr := os.Lstat(src); statErr == nil && info.Mode().IsRegular() {
		if err := requireSha(expectedSha); err != nil {
			return nil, err
		}
		hash, hashErr := fileSum(src)
		if hashErr != nil || hash != expectedSha {
			return nil, guard.Errf(guard.Conflict, "File changed since it was read")
		}
	}
	dst, err := guard.ResolveNonExisting(root, target, false)
	if err != nil {
		return nil, err
	}
	if guard.Under(src, dst) {
		return nil, guard.Errf(guard.Invalid, "Workspace entry cannot be moved into itself")
	}
	if err := os.Rename(src, dst); err != nil {
		return nil, guard.Errf(guard.Invalid, "Could not move workspace entry")
	}
	return entry(root, dst)
}

// Delete removes a file, an empty directory, or a directory tree when recursive is set.
func Delete(root, requested, expectedSha string, recursive bool) error {
	target, err := guard.Resolve(root, requested)
	if err != nil {
		return err
	}
	if target == root {
		return guard.Errf(guard.Invalid, "Workspace root cannot be deleted")
	}
	info, err := os.Lstat(target)
	if err != nil {
		return guard.Errf(guard.Invalid, "Workspace path does not exist")
	}
	switch {
	case info.Mode().IsRegular():
		if err := requireSha(expectedSha); err != nil {
			return err
		}
		hash, hashErr := fileSum(target)
		if hashErr != nil || hash != expectedSha {
			return guard.Errf(guard.Conflict, "File changed since it was read")
		}
	case info.IsDir() && !recursive:
		children, readErr := os.ReadDir(target)
		if readErr != nil {
			return guard.Errf(guard.Invalid, "Could not inspect workspace directory")
		}
		if len(children) > 0 {
			return guard.Errf(guard.Invalid, "Directory is not empty; recursive confirmation is required")
		}
	}
	var paths []string
	hasSymlink := false
	_ = filepath.WalkDir(target, func(path string, entry fs.DirEntry, err error) error {
		if err != nil {
			return nil
		}
		if entry.Type()&fs.ModeSymlink != 0 {
			hasSymlink = true
		}
		paths = append(paths, path)
		return nil
	})
	if hasSymlink {
		return guard.Errf(guard.Invalid, "Symbolic links are not supported by workspace tools")
	}
	for i := len(paths) - 1; i >= 0; i-- {
		if err := os.Remove(paths[i]); err != nil {
			return guard.Errf(guard.Invalid, "Could not delete workspace entry")
		}
	}
	return nil
}
