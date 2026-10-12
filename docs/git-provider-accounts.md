# Git provider accounts

Petri supports two project sources: connected accounts and Simple Git.

## Connected accounts

Open **Git accounts** and connect GitHub, GitLab or Forgejo with a personal access token. If an OAuth client is configured, OAuth with PKCE is available too. Multiple accounts on one provider instance are supported.

Built-in presets exist for GitHub.com and GitLab.com. Every user can add personal GitHub Enterprise Server, GitLab Self-Managed and Forgejo installations under **Self-hosted instances**. Personal endpoints and OAuth credentials are visible and usable only by their owner. Administrators can additionally maintain global instances that are available to all users. HTTP endpoints are accepted for trusted networks but are visibly marked insecure.

For PAT-only connections, configure the web and API base URLs; OAuth fields may remain empty. Common API base paths are `/api/v3` for GitHub Enterprise, `/api/v4` for GitLab and `/api/v1` for Forgejo. Then return to **Git accounts**, select the configured instance and connect the token.

PATs are checked against identity and repository APIs before encryption. OAuth state is short-lived and bound to the user, provider and PKCE verifier. Forgejo OAuth has a mandatory warning because it currently cannot restrict tokens by scope.

The project form loads a live, paginated repository catalog. During bulk import Petri reloads every repository by provider ID; browser-supplied clone URLs and branches are not trusted. Results are independent: `CREATED`, `ALREADY_EXISTS` or `FAILED`.

Mirror jobs persist, recover stale leases after restart and use at most `petri.imports.max-parallel` workers (default `4`). Failed jobs can be retried from the project list.

Disconnecting deletes token material immediately. Existing projects remain visible but remote operations fail closed until reconnect. A project may explicitly switch to project-specific Simple Git credentials; there is no silent fallback.

Read-only repositories can be imported and inspected. Push and remote-branch deletion are disabled in the UI and enforced by the server.

## Simple Git

The **Simple Git** tab preserves direct creation with a repository URI, default branch and optional project credential. HTTPS, SSH and local paths remain supported. Use unrestricted targets only when every Petri user is trusted.

## Ownership

Projects, worktrees, chats, runs, proposals, tests and API keys are owner-scoped. Foreign identifiers return `404`. API keys can access only projects owned by their creator.

Administrators manage global provider instances, global OAuth applications, model endpoints, audit data and sandbox profiles. Regular users manage their personal self-hosted instances and OAuth applications as well as their own accounts, projects, workspaces, chats, Git actions and API keys.

## Updating a token

Accounts connected with an access token have an **Update token** action in the account list. Paste a
new token: Petri checks it with the provider (it must belong to the same account and be able to
list repositories) and only then replaces the stored one, keeping the account and every project that
uses it. Use it when a token expired or when it needs more permissions, for example Forgejo's
`write:issue` scope to comment on pull requests. A token of a different account is refused; connect
that one as a separate account. OAuth accounts renew by disconnecting and connecting again.

