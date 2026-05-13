# Grimmory acquisition stack

This Helm chart deploys Grimmory with the complete acquisition stack:

- Grimmory API/UI
- MariaDB
- FlareSolverr
- Prowlarr
- qBittorrent
- Stacks
- post-install source bootstrap for Prowlarr, MangaDex, Anna's Archive via Stacks, and Gallery-DL

## Install

```bash
helm upgrade --install grimmory-stack deploy/helm/grimmory-stack \
  --namespace grimmory \
  --create-namespace \
  --wait \
  --wait-for-jobs \
  --timeout 20m \
  --set-string secrets.database.password='<database-password>' \
  --set-string secrets.database.rootPassword='<database-root-password>' \
  --set-string secrets.admin.password='<grimmory-admin-password>' \
  --set-string secrets.prowlarr.apiKey='<prowlarr-api-key>' \
  --set-string secrets.qbittorrent.password='<qbittorrent-password>' \
  --set-string secrets.stacks.apiKey='<stacks-admin-api-key>' \
  --set-string secrets.stacks.downloaderKey='<stacks-downloader-api-key>' \
  --set-string secrets.stacks.sessionSecret='<stacks-session-secret>'
```

If Longhorn free space is tight, pin the stack to a node with enough local storage:

```bash
--set global.nodeSelector.kubernetes\\.io/hostname='<node-name>'
```

## Ingress

Default hosts:

- `grimmory.raphcvr.me`
- `prowlarr.raphcvr.me`
- `qbit.raphcvr.me`
- `stacks.raphcvr.me`

FlareSolverr stays internal and is only exposed as `http://flaresolverr:8191` inside the namespace.

## Storage

`bookdrop` is mounted by Grimmory, qBittorrent and Stacks. Stacks also mounts the same PVC at `/opt/stacks/download` and `/opt/stacks/download/incomplete`, so files acquired by Stacks are visible to Grimmory under `/bookdrop/.downloads/stacks-cache`.

The chart creates `longhorn-grimmory-single` for `bookdrop` and `grimmory-books` to avoid multi-replica scheduling failures on small clusters. PVCs are expandable.

## Auto-update

Keel annotations are enabled by default on app deployments:

- `keel.sh/policy=force`
- `keel.sh/trigger=poll`
- `keel.sh/pollSchedule=@every 1m`
- `keel.sh/match-tag=true`

Use a mutable image tag for components that should be updated automatically.
