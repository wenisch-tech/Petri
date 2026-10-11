/*
 * petri-confine: run a command with Landlock filesystem confinement and no privileges.
 *
 *   petri-confine [--ro PATH]... [--rw PATH]... -- COMMAND [ARGS...]
 *   petri-confine --check
 *
 * Everything not granted is denied (reads, writes, execs, creation), regardless of the Unix user.
 * Works for any uid (including random container uids) without root, setuid or user namespaces.
 * Missing paths are skipped. Exit status 125 means confinement could not be applied; the command
 * is then NOT run (fail closed).
 */
#define _GNU_SOURCE
#include <errno.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/prctl.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

#ifndef SYS_landlock_create_ruleset
#define SYS_landlock_create_ruleset 444
#define SYS_landlock_add_rule 445
#define SYS_landlock_restrict_self 446
#endif

#define LL_CREATE_RULESET_VERSION (1U << 0)
#define LL_RULE_PATH_BENEATH 1

#define A_EXECUTE (1ULL << 0)
#define A_WRITE_FILE (1ULL << 1)
#define A_READ_FILE (1ULL << 2)
#define A_READ_DIR (1ULL << 3)
#define A_TRUNCATE (1ULL << 14)
#define A_IOCTL_DEV (1ULL << 15)

struct ruleset_attr { uint64_t handled_access_fs; uint64_t handled_access_net; };
struct path_beneath { uint64_t allowed_access; int32_t parent_fd; } __attribute__((packed));

static int abi(void) {
  return (int)syscall(SYS_landlock_create_ruleset, NULL, 0, LL_CREATE_RULESET_VERSION);
}

static uint64_t handled_for(int version) {
  uint64_t mask = (1ULL << 13) - 1;              /* v1: bits 0..12 */
  if (version >= 2) mask |= 1ULL << 13;          /* REFER */
  if (version >= 3) mask |= A_TRUNCATE;
  if (version >= 5) mask |= A_IOCTL_DEV;
  return mask;
}

static int add_path(int ruleset, const char *path, uint64_t access, uint64_t handled) {
  int fd = open(path, O_PATH | O_CLOEXEC);
  if (fd < 0) return 0;                          /* skip missing paths */
  struct stat st;
  if (fstat(fd, &st) == 0 && !S_ISDIR(st.st_mode)) {
    access &= (A_EXECUTE | A_WRITE_FILE | A_READ_FILE | A_TRUNCATE | A_IOCTL_DEV);
  }
  struct path_beneath rule = { .allowed_access = access & handled, .parent_fd = fd };
  long rc = syscall(SYS_landlock_add_rule, ruleset, LL_RULE_PATH_BENEATH, &rule, 0);
  close(fd);
  return rc == 0 ? 0 : -1;
}

int main(int argc, char **argv) {
  int version = abi();
  if (argc == 2 && strcmp(argv[1], "--check") == 0) {
    if (version < 1) { fprintf(stderr, "landlock unavailable (%s)\n", strerror(errno)); return 125; }
    printf("landlock abi %d\n", version);
    return 0;
  }
  if (version < 1) {
    fprintf(stderr, "petri-confine: Landlock is not available on this kernel/container\n");
    return 125;
  }
  uint64_t handled = handled_for(version);
  uint64_t ro = (A_EXECUTE | A_READ_FILE | A_READ_DIR) & handled;
  struct ruleset_attr attr = { .handled_access_fs = handled, .handled_access_net = 0 };
  size_t attr_size = version >= 4 ? sizeof(attr) : sizeof(uint64_t);
  int ruleset = (int)syscall(SYS_landlock_create_ruleset, &attr, attr_size, 0);
  if (ruleset < 0) { perror("petri-confine: create ruleset"); return 125; }

  int i = 1;
  for (; i < argc; i++) {
    if (strcmp(argv[i], "--") == 0) { i++; break; }
    int rw = strcmp(argv[i], "--rw") == 0;
    if ((rw || strcmp(argv[i], "--ro") == 0) && i + 1 < argc) {
      if (add_path(ruleset, argv[++i], rw ? handled : ro, handled) != 0) {
        perror("petri-confine: add rule");
        return 125;
      }
    } else {
      fprintf(stderr, "petri-confine: unexpected argument %s\n", argv[i]);
      return 125;
    }
  }
  if (i >= argc) { fprintf(stderr, "petri-confine: no command\n"); return 125; }
  if (prctl(PR_SET_NO_NEW_PRIVS, 1, 0, 0, 0) != 0) { perror("petri-confine: no_new_privs"); return 125; }
  if (syscall(SYS_landlock_restrict_self, ruleset, 0) != 0) { perror("petri-confine: restrict"); return 125; }
  close(ruleset);
  execvp(argv[i], &argv[i]);
  fprintf(stderr, "petri-confine: exec %s: %s\n", argv[i], strerror(errno));
  return 127;
}
