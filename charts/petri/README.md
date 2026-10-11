# Petri Helm chart

Deploys [Petri](https://github.com/wenisch-tech/Petri), a self-hosted agentic coding harness,
multi-agent orchestrator and development environment.

The chart source lives in this repository. Released versions are signed with Cosign and published to
the [Wenisch Tech Helm repository](https://github.com/wenisch-tech/helm-charts)
(<https://charts.wenisch.tech>) and to `oci://ghcr.io/wenisch-tech/helm-charts/petri`.

```bash
helm repo add wenisch-tech https://charts.wenisch.tech
helm repo update
helm install petri wenisch-tech/petri -n petri --create-namespace \
  --set secrets.PETRI_ADMIN_PASSWORD='change-me' \
  --set secrets.PETRI_SECRETS_MASTER_KEY="$(openssl rand -base64 32)"
```

## Notes

- **Persistence** is enabled by default (`/app/data`: embedded H2, mirrors, worktrees and the master
  key). The embedded database allows a single writer, so the chart uses the `Recreate` strategy.
  Use PostgreSQL (`env.SPRING_DATASOURCE_URL`, `secrets.SPRING_DATASOURCE_PASSWORD`) for anything else.
- **Master key:** set `secrets.PETRI_SECRETS_MASTER_KEY` or let Petri generate one on the volume, and
  back it up either way.
- **Tests:** Petri runs tests in rootless Podman. The container image does not ship Podman, so
  sandboxed test execution needs a node/runtime that provides it and is out of scope for this chart.
- **OIDC** is configured through standard `SPRING_SECURITY_OAUTH2_CLIENT_*` environment variables;
  see `values.yaml`.
