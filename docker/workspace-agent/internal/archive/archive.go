// Package archive moves directory trees in and out of the pod as tar streams.
package archive

import (
	"archive/tar"
	"io"
	"io/fs"
	"os"
	"path/filepath"

	"petri.wenisch.tech/workspace-agent/internal/guard"
)

// Plan counts what a tree would contain so that an oversized tree is refused before streaming.
type Plan struct {
	Files int
	Bytes int64
}

func skipped(skip map[string]bool, root, path string, entry fs.DirEntry) bool {
	return path != root && entry.IsDir() && skip[entry.Name()]
}

// Measure walks the tree the same way Write will.
func Measure(root string, skip map[string]bool) (Plan, error) {
	var plan Plan
	err := filepath.WalkDir(root, func(path string, entry fs.DirEntry, err error) error {
		if err != nil {
			return nil
		}
		if skipped(skip, root, path, entry) {
			return filepath.SkipDir
		}
		if entry.Type().IsRegular() {
			info, infoErr := entry.Info()
			if infoErr == nil {
				plan.Files++
				plan.Bytes += info.Size()
			}
		}
		return nil
	})
	return plan, err
}

// Write streams the directories and regular files of root, skipping the named directories.
// Symbolic links and special files are left out.
func Write(out io.Writer, root string, skip map[string]bool) error {
	archive := tar.NewWriter(out)
	err := filepath.WalkDir(root, func(path string, entry fs.DirEntry, err error) error {
		if err != nil {
			return nil
		}
		if skipped(skip, root, path, entry) {
			return filepath.SkipDir
		}
		info, infoErr := entry.Info()
		if infoErr != nil {
			return nil
		}
		rel := guard.Relative(root, path)
		switch {
		case info.IsDir():
			if rel == "." {
				return nil
			}
			header, _ := tar.FileInfoHeader(info, "")
			header.Name = rel + "/"
			return archive.WriteHeader(header)
		case info.Mode().IsRegular():
			header, _ := tar.FileInfoHeader(info, "")
			header.Name = rel
			if err := archive.WriteHeader(header); err != nil {
				return err
			}
			file, openErr := os.Open(path)
			if openErr != nil {
				return openErr
			}
			defer file.Close()
			_, copyErr := io.CopyN(archive, file, header.Size)
			return copyErr
		}
		return nil
	})
	if err != nil {
		return err
	}
	return archive.Close()
}

// Extract unpacks directories and regular files below dest. Anything else, and any entry that
// would leave dest or cross a symbolic link, is refused.
func Extract(in io.Reader, dest string, maxBytes int64) error {
	reader := tar.NewReader(in)
	var total int64
	for {
		header, err := reader.Next()
		if err == io.EOF {
			return nil
		}
		if err != nil {
			return guard.Errf(guard.Invalid, "Archive is not a valid tar stream")
		}
		target, err := guard.SafeJoin(dest, header.Name)
		if err != nil {
			return err
		}
		switch header.Typeflag {
		case tar.TypeDir:
			if err := os.MkdirAll(target, 0o755); err != nil {
				return guard.Errf(guard.Invalid, "Could not create directory from archive")
			}
		case tar.TypeReg:
			total += header.Size
			if maxBytes > 0 && total > maxBytes {
				return guard.Errf(guard.TooLarge, "Archive is too large")
			}
			if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
				return guard.Errf(guard.Invalid, "Could not create directory from archive")
			}
			mode := fs.FileMode(header.Mode) & 0o777
			file, err := os.OpenFile(target, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, mode)
			if err != nil {
				return guard.Errf(guard.Invalid, "Could not write file from archive")
			}
			if _, err := io.Copy(file, reader); err != nil {
				file.Close()
				return guard.Errf(guard.Invalid, "Could not write file from archive")
			}
			if err := file.Close(); err != nil {
				return guard.Errf(guard.Invalid, "Could not write file from archive")
			}
		default:
			return guard.Errf(guard.Invalid, "Archive entry type is not supported")
		}
	}
}

// Copy duplicates the directories and regular files of source below destination, skipping the
// named directories, and keeps file modes. It reports false, without copying, when the tree holds
// more than maxBytes or maxFiles (a limit of zero or less means unlimited). Symbolic links and
// special files are left out, like in an archive.
func Copy(source, destination string, skip map[string]bool, maxBytes int64, maxFiles int) (bool, error) {
	plan, err := Measure(source, skip)
	if err != nil {
		return false, err
	}
	if (maxBytes > 0 && plan.Bytes > maxBytes) || (maxFiles > 0 && plan.Files > maxFiles) {
		return false, nil
	}
	err = filepath.WalkDir(source, func(path string, entry fs.DirEntry, walkErr error) error {
		if walkErr != nil {
			return nil
		}
		if skipped(skip, source, path, entry) {
			return filepath.SkipDir
		}
		info, infoErr := entry.Info()
		if infoErr != nil {
			return nil
		}
		target := filepath.Join(destination, guard.Relative(source, path))
		switch {
		case info.IsDir():
			return os.MkdirAll(target, 0o755)
		case info.Mode().IsRegular():
			return copyFile(path, target, info.Mode().Perm())
		}
		return nil
	})
	return err == nil, err
}

func copyFile(from, to string, mode fs.FileMode) error {
	in, err := os.Open(from)
	if err != nil {
		return err
	}
	defer in.Close()
	out, err := os.OpenFile(to, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, mode)
	if err != nil {
		return err
	}
	if _, err := io.Copy(out, in); err != nil {
		out.Close()
		return err
	}
	return out.Close()
}
