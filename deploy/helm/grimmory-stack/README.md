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

Create the runtime secret outside Helm first. This avoids storing real credentials in Helm release history:

```bash
kubectl create namespace grimmory
kubectl create secret generic grimmory-runtime-secrets \
  --namespace grimmory \
  --from-literal=db-username='grimmory' \
  --from-literal=db-password='<database-password>' \
  --from-literal=db-root-password='<database-root-password>' \
  --from-literal=admin-username='admin' \
  --from-literal=admin-password='<grimmory-admin-password>' \
  --from-literal=prowlarr-api-key='<prowlarr-api-key>' \
  --from-literal=qbittorrent-username='admin' \
  --from-literal=qbittorrent-password='<qbittorrent-password>' \
  --from-literal=qbittorrent-password-pbkdf2='<qbittorrent-pbkdf2-password>' \
  --from-literal=stacks-username='admin' \
  --from-literal=stacks-password-bcrypt='<stacks-bcrypt-password>' \
  --from-literal=stacks-api-key='<stacks-admin-api-key>' \
  --from-literal=stacks-downloader-key='<stacks-downloader-api-key>' \
  --from-literal=stacks-session-secret='<stacks-session-secret>'
```

```bash
helm upgrade --install grimmory-stack deploy/helm/grimmory-stack \
  --namespace grimmory \
  --create-namespace \
  --wait \
  --wait-for-jobs \
  --timeout 20m \
  --set secrets.create=false \
  --set secrets.existingSecret=grimmory-runtime-secrets
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

The chart creates `longhorn-grimmory-single` for `bookdrop` and `grimmory-books` to avoid multi-replica scheduling failures on small clusters. PVCs are expandable and annotated with `helm.sh/resource-policy=keep` by default.

## Auto-update

Keel annotations are enabled by default on app deployments:

- `keel.sh/policy=force`
- `keel.sh/trigger=poll`
- `keel.sh/pollSchedule=@every 1m`
- `keel.sh/match-tag=true`

Use a mutable image tag for components that should be updated automatically.
