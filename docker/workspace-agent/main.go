// Command petri-workspace-agent runs inside a Petri Kubernetes workspace pod. It serves a small
// authenticated HTTP API for file access, process execution and archives below one workspace root.
package main

import (
	"fmt"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"

	"petri.wenisch.tech/workspace-agent/internal/guard"
	"petri.wenisch.tech/workspace-agent/internal/server"
)

var version = "dev"

func main() {
	command := "serve"
	if len(os.Args) > 1 {
		command = os.Args[1]
	}
	switch command {
	case "serve":
		os.Exit(serve())
	case "askpass":
		askpass(os.Args[2:])
	case "version":
		fmt.Println(version)
	default:
		fmt.Fprintf(os.Stderr, "usage: %s serve|askpass|version\n", filepath.Base(os.Args[0]))
		os.Exit(2)
	}
}

// askpass answers Git's credential prompts from the environment, like Petri's local helper script.
func askpass(args []string) {
	prompt := strings.Join(args, " ")
	if strings.Contains(prompt, "Username") {
		fmt.Println(os.Getenv("PETRI_GIT_USERNAME"))
		return
	}
	fmt.Println(os.Getenv("PETRI_GIT_SECRET"))
}

func serve() int {
	token := os.Getenv("PETRI_AGENT_TOKEN")
	if token == "" {
		fmt.Fprintln(os.Stderr, "PETRI_AGENT_TOKEN is required")
		return 1
	}
	rootValue := os.Getenv("PETRI_WORKSPACE_ROOT")
	if rootValue == "" {
		rootValue = "/petri/workspace"
	}
	if err := os.MkdirAll(rootValue, 0o755); err != nil {
		fmt.Fprintf(os.Stderr, "workspace root %s is not usable: %v\n", rootValue, err)
		return 1
	}
	root, err := guard.RealRoot(rootValue)
	if err != nil {
		fmt.Fprintf(os.Stderr, "workspace root %s is not usable: %v\n", rootValue, err)
		return 1
	}
	listen := os.Getenv("PETRI_AGENT_LISTEN")
	if listen == "" {
		listen = ":7777"
	}
	api := &server.Server{Root: root, Token: token, Version: version}
	httpServer := &http.Server{Addr: listen, Handler: api.Handler(), ReadHeaderTimeout: 10 * time.Second}
	fmt.Printf("petri-workspace-agent %s serving %s on %s\n", version, root, listen)
	if err := httpServer.ListenAndServe(); err != nil {
		fmt.Fprintf(os.Stderr, "server stopped: %v\n", err)
		return 1
	}
	return 0
}
