// Package toolchain installs the bundle of command line tools (git, python, go, curl, node ...) that
// Petri ships for pods whose image lacks them, for example in networks without internet access.
//
// The bundle is a tar archive, optionally gzip-compressed, whose binaries were prepared for one fixed
// installation prefix. Unlike the archives of the workspace it carries symbolic links and hard links,
// so it has its own extractor that refuses anything leaving the installation directory.
package toolchain

import (
	"archive/tar"
	"bufio"
	"compress/gzip"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"os"
	"path/filepath"
	"sort"
	"strings"
)

// MarkerFile is written by the bundle builder as the last entry and describes the bundle.
const MarkerFile = ".petri-toolchain.json"

// Marker describes an installed bundle.
type Marker struct {
	Version string `json:"version"`
	Prefix  string `json:"prefix"`
	Arch    string `json:"arch"`
}

// Info is what the agent reports about the installation directory.
type Info struct {
	Present bool     `json:"present"`
	Version string   `json:"version"`
	Arch    string   `json:"arch"`
	Prefix  string   `json:"prefix"`
	Tools   []string `json:"tools"`
}

// Read reports what is installed in dir.
func Read(dir string) Info {
	data, err := os.ReadFile(filepath.Join(dir, MarkerFile))
	if err != nil {
		return Info{Tools: []string{}}
	}
	var marker Marker
	if json.Unmarshal(data, &marker) != nil {
		return Info{Tools: []string{}}
	}
	info := Info{Present: true, Version: marker.Version, Arch: marker.Arch, Prefix: marker.Prefix, Tools: []string{}}
	entries, err := os.ReadDir(filepath.Join(dir, "bin"))
	if err == nil {
		for _, entry := range entries {
			info.Tools = append(info.Tools, entry.Name())
		}
		sort.Strings(info.Tools)
	}
	return info
}

// Install unpacks the bundle read from in into dir, replacing a previous installation. The files are
// first written next to dir and only moved into place when the whole bundle was valid, so a failed
// transfer leaves the previous installation intact. maxBytes limits the unpacked size.
func Install(in io.Reader, dir string, maxBytes int64) (Info, error) {
	parent := filepath.Dir(dir)
	if err := os.MkdirAll(parent, 0o755); err != nil {
		return Info{}, fmt.Errorf("cannot create %s: %w", parent, err)
	}
	staging, err := os.MkdirTemp(parent, ".toolchain-staging-")
	if err != nil {
		return Info{}, fmt.Errorf("cannot create a staging directory: %w", err)
	}
	defer os.RemoveAll(staging)
	if err := extract(in, staging, maxBytes); err != nil {
		return Info{}, err
	}
	data, err := os.ReadFile(filepath.Join(staging, MarkerFile))
	if err != nil {
		return Info{}, errors.New("the bundle has no " + MarkerFile)
	}
	var marker Marker
	if json.Unmarshal(data, &marker) != nil || marker.Version == "" {
		return Info{}, errors.New("the bundle's " + MarkerFile + " is not valid")
	}
	if marker.Prefix != dir {
		return Info{}, fmt.Errorf("the bundle was built for %s but this pod installs it at %s", marker.Prefix, dir)
	}
	if err := os.RemoveAll(dir); err != nil {
		return Info{}, fmt.Errorf("cannot replace the previous installation: %w", err)
	}
	if err := os.Rename(staging, dir); err != nil {
		return Info{}, fmt.Errorf("cannot move the bundle into place: %w", err)
	}
	return Read(dir), nil
}

func extract(in io.Reader, dest string, maxBytes int64) error {
	buffered := bufio.NewReader(in)
	var source io.Reader = buffered
	if magic, err := buffered.Peek(2); err == nil && magic[0] == 0x1f && magic[1] == 0x8b {
		unzipped, err := gzip.NewReader(buffered)
		if err != nil {
			return errors.New("the bundle is not a valid gzip stream")
		}
		defer unzipped.Close()
		source = unzipped
	}
	reader := tar.NewReader(source)
	var total int64
	for {
		header, err := reader.Next()
		if err == io.EOF {
			return nil
		}
		if err != nil {
			return fmt.Errorf("the bundle is not a valid tar stream: %w", err)
		}
		name, err := cleanName(header.Name)
		if err != nil {
			return err
		}
		if name == "" {
			continue
		}
		target := filepath.Join(dest, name)
		if err := requirePlainParents(dest, target); err != nil {
			return err
		}
		switch header.Typeflag {
		case tar.TypeDir:
			if err := os.MkdirAll(target, 0o755); err != nil {
				return fmt.Errorf("cannot create %s: %w", name, err)
			}
		case tar.TypeReg:
			total += header.Size
			if maxBytes > 0 && total > maxBytes {
				return errors.New("the bundle is too large")
			}
			if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
				return fmt.Errorf("cannot create the directory of %s: %w", name, err)
			}
			// No setuid or setgid bits; the owner can always write, so a later removal works.
			mode := fs.FileMode(header.Mode)&0o777 | 0o200
			_ = os.Remove(target)
			file, err := os.OpenFile(target, os.O_CREATE|os.O_WRONLY|os.O_EXCL, mode)
			if err != nil {
				return fmt.Errorf("cannot write %s: %w", name, err)
			}
			if _, err := io.Copy(file, reader); err != nil {
				file.Close()
				return fmt.Errorf("cannot write %s: %w", name, err)
			}
			if err := file.Close(); err != nil {
				return fmt.Errorf("cannot write %s: %w", name, err)
			}
		case tar.TypeSymlink:
			if strings.ContainsRune(header.Linkname, 0) || header.Linkname == "" {
				return fmt.Errorf("the link %s has an invalid target", name)
			}
			if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
				return fmt.Errorf("cannot create the directory of %s: %w", name, err)
			}
			_ = os.Remove(target)
			if err := os.Symlink(header.Linkname, target); err != nil {
				return fmt.Errorf("cannot link %s: %w", name, err)
			}
		case tar.TypeLink:
			source, err := cleanName(header.Linkname)
			if err != nil || source == "" {
				return fmt.Errorf("the hard link %s has an invalid target", name)
			}
			if err := requirePlainParents(dest, filepath.Join(dest, source)); err != nil {
				return err
			}
			if err := os.MkdirAll(filepath.Dir(target), 0o755); err != nil {
				return fmt.Errorf("cannot create the directory of %s: %w", name, err)
			}
			_ = os.Remove(target)
			if err := os.Link(filepath.Join(dest, source), target); err != nil {
				return fmt.Errorf("cannot link %s: %w", name, err)
			}
		case tar.TypeXGlobalHeader, tar.TypeXHeader:
			// metadata only
		default:
			// Devices, FIFOs and the like have no place in a toolchain; leave them out.
		}
	}
}

// cleanName turns a tar entry name into a relative path inside the bundle ("" for the root itself).
func cleanName(name string) (string, error) {
	if strings.ContainsRune(name, 0) {
		return "", errors.New("the bundle has an entry with an invalid name")
	}
	trimmed := strings.TrimPrefix(filepath.ToSlash(name), "./")
	cleaned := filepath.Clean(trimmed)
	if cleaned == "." || cleaned == "" {
		return "", nil
	}
	if filepath.IsAbs(cleaned) || cleaned == ".." || strings.HasPrefix(cleaned, "../") {
		return "", fmt.Errorf("the bundle entry %q leaves the installation directory", name)
	}
	return cleaned, nil
}

// requirePlainParents refuses to write below a symbolic link, which could lead out of dest.
func requirePlainParents(dest, target string) error {
	current := dest
	rel, err := filepath.Rel(dest, filepath.Dir(target))
	if err != nil || rel == "." {
		return nil
	}
	for _, part := range strings.Split(rel, string(filepath.Separator)) {
		current = filepath.Join(current, part)
		info, err := os.Lstat(current)
		if errors.Is(err, fs.ErrNotExist) {
			return nil
		}
		if err != nil {
			return err
		}
		if info.Mode()&os.ModeSymlink != 0 {
			return fmt.Errorf("the bundle writes below the link %s", part)
		}
	}
	return nil
}
