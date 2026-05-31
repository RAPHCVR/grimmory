# Grimmory downloads/acquisition hardening tracker

Scope hard constraints:

- Repository: `C:\Users\Raphael\Documents\Grimmory`.
- Branch: `feat/downloads-acquisition`.
- Do not modify BookLore.
- Keep secrets out of Git; runtime secrets stay in Kubernetes secrets.

Current confirmed baseline:

- Grimmory branch is pushed/synced to origin.
- Runtime image tag: `ghcr.io/raphcvr/grimmory:downloads-acquisition`.
- Runtime version after last rollout: `downloads-acquisition-f820ebe3`.
- Runtime digest after last rollout: `sha256:9906854902e8b99c5ee0f4376e1d2152d72ddc1010abe72e37e901198f410f85`.
- Current DB counts verified after post-rollout smoke cleanup: users=1, books=2, libraries=1, download_sources=5, download_jobs=0.
- Current known good DB dump previously verified: `/dumps/grimmory/20260531T174114Z_users-1_books-2_libraries-1.sql.gz`.
- BookLore local folder is only a placeholder/shortcut, not a Git repo.

Work items from the last two conversations:

## P0 guardrails

- Preserve Grimmory only; do not touch BookLore repo/state.
- Do not commit raw credentials.
- Before destructive cluster/local cleanup: prove target path/resource and preserve data.

## P1 runtime/data safety

- Prove DB restore from the latest logical dump in an isolated temporary MariaDB workload.
- Validate 4 controlled acquisitions end-to-end when safe payloads are available:
  - Anna/Stacks small EPUB/PDF.
  - MangaDex short chapter.
  - Webtoon/Gallery-dl/Kagane short episode.
  - Prowlarr/qBittorrent small safe payload.
- Ensure backup/export story is not only in-cluster: evaluate off-cluster backup option, implement only if a safe target is already available.

## P1/P2 product quality

- Keep volume/chapter/issue/episode disambiguation reliable.
- Keep video/anime torrent noise penalized for MANGA/COMIC/WEBTOON.
- Keep Webtoon global search reliable for known and less obvious titles.
- Consider true two-step canonical resolver UX: resolve canonical work first, then acquire locked selection.

## P2 platform hardening

- Harden Kubernetes securityContext where compatible:
  - `allowPrivilegeEscalation: false`.
  - `capabilities.drop: ["ALL"]`.
  - `runAsNonRoot: true` where image supports it.
  - `seccompProfile.type: RuntimeDefault`.
- Preserve linuxserver/root-required containers unless verified compatible.
- Keep MariaDB StatefulSet protected from accidental data-dir reinit.

## Validation expectations

- For code changes: targeted tests, then backend build or relevant wider suite.
- For Helm changes: `helm lint`, `helm template`, server dry-run when useful, then rollout verification.
- For runtime: pod Running/Ready, image digest, healthcheck, DB counts, provider pings.
- Final state: Git clean and pushed if changes are made.

## Evidence log

### 2026-05-31 restore drill

- Temporary pod: `grimmory-restore-drill` in namespace `grimmory`.
- Source PVC mounted read-only: `grimmory-mariadb-dumps`.
- Temporary database storage: `emptyDir`.
- Restored dump: `/dumps/grimmory/20260531T174114Z_users-1_books-2_libraries-1.sql.gz`.
- Restored counts:
  - users=1
  - books=2
  - libraries=1
  - download_sources=5
- Temporary pod deleted after validation.
- Production MariaDB was not modified.

### 2026-05-31 controlled acquisition smoke

- Method: temporary in-cluster smoke provider and temporary download sources; real sources id 1..5 were disabled only during the smoke and restored immediately after.
- Direct file executor: `DIRECT_FILE` EPUB -> `PENDING_REVIEW`.
- MangaDex executor: fake MangaDex API + at-home image pages -> `MANGADEX_CHAPTER` CBZ -> `PENDING_REVIEW`.
- Gallery-dl executor: controlled fake gallery-dl binary on shared bookdrop PVC -> `CLI_GALLERY_DL` CBZ -> `PENDING_REVIEW`.
- Kagane executor: fake Kagane chapter URL fetched through real FlareSolverr -> `KAGANE_CHAPTER` CBZ -> `PENDING_REVIEW`.
- Prowlarr/qBit executor: fake Prowlarr API result + fake qBittorrent API writing to the real staging PVC -> `TORRENT` EPUB -> `PENDING_REVIEW`.
- Cleanup verified after run:
  - real sources enabled: Prowlarr, MangaDex, Anna/Stacks, Webtoons/Gallery-dl, Kagane.
  - temporary smoke sources: 0.
  - temporary smoke searches/jobs/bookdrop rows: 0.
  - temporary smoke files/resources: none found.

### 2026-05-31 off-cluster backup export

- Manual logical dump triggered from `grimmory-mariadb-logical-dump` CronJob.
- Exported latest dump from PVC `grimmory-mariadb-dumps` to local off-cluster folder:
  - `C:\Users\Raphael\Documents\GrimmoryBackups\mariadb-dumps\20260531T184156Z_users-1_books-2_libraries-1.sql.gz`
  - size: 913301 bytes.
  - sha256: `d578a8dbe75aa2723c5994b80cd7ff815e05f58bedfafe7aec2123d0a4c4ec46`.
- Gzip integrity test: OK.
- Temporary exporter pod and manual dump job cleaned up after export.

### 2026-05-31 local validation before resolver/hardening rollout

- Repository: `C:\Users\Raphael\Documents\Grimmory`.
- Branch: `feat/downloads-acquisition`.
- Scope check: `C:\Users\Raphael\Documents\BookLore` is not a Git repo; it only contains the shortcut to the real Grimmory repo.
- Current production DB counts before rollout:
  - users=1
  - books=2
  - libraries=1
  - download_sources=5
- Git diff hygiene:
  - `git diff --check`: OK; only Windows LF-to-CRLF warnings from Git.
- Helm validation:
  - `helm lint deploy\helm\grimmory-stack`: OK; only chart icon recommendation.
  - `helm template grimmory-stack deploy\helm\grimmory-stack --namespace grimmory`: OK.
  - `helm upgrade grimmory-stack deploy\helm\grimmory-stack -n grimmory --reuse-values --dry-run=server`: OK.
- Backend validation:
  - Targeted: `.\gradlew.bat --no-daemon test --tests org.booklore.service.downloads.DownloadCanonicalResolverTest --tests org.booklore.service.downloads.DownloadQueryIntentParserTest --tests org.booklore.service.downloads.DownloadScoringServiceTest`: OK.
  - Full: `.\gradlew.bat --no-daemon test`: OK.
- Frontend validation:
  - `corepack yarn build:prod`: OK.
  - `corepack yarn typecheck`: OK.
  - `corepack yarn lint`: OK.
  - `corepack yarn test`: OK, 289 files passed, 1580 tests passed, 90 files/148 tests skipped by existing suite config.
- Generated artifacts are ignored and not staged:
  - `backend/build/`
  - `frontend/.angular/`
  - `frontend/dist/`
  - `frontend/test-results/`
  - `frontend/node_modules/`

### 2026-05-31 rollout revision 19 and post-rollout smoke

- Built and pushed Docker image:
  - tag: `ghcr.io/raphcvr/grimmory:downloads-acquisition`.
  - revision: `f820ebe38b3bfc470d3ee922191d23624add61e0`.
  - digest: `sha256:9906854902e8b99c5ee0f4376e1d2152d72ddc1010abe72e37e901198f410f85`.
- Helm rollout:
  - release: `grimmory-stack`.
  - namespace: `grimmory`.
  - revision: `19`.
  - status: `deployed`.
- Runtime health:
  - pod: `grimmory-56cd55b5f6-n4hr7`.
  - image: `ghcr.io/raphcvr/grimmory:downloads-acquisition`.
  - imageID: `ghcr.io/raphcvr/grimmory@sha256:9906854902e8b99c5ee0f4376e1d2152d72ddc1010abe72e37e901198f410f85`.
  - healthcheck: `UP`.
  - version: `downloads-acquisition-f820ebe3`.
- Grimmory container security:
  - `runAsUser=1000`, `runAsGroup=1000`, `runAsNonRoot=true`.
  - `allowPrivilegeEscalation=false`.
  - `capabilities.drop=["ALL"]`.
  - `seccompProfile.type=RuntimeDefault`.
  - runtime `id`: `uid=1000 gid=1000 groups=1000`.
- Source bootstrap after rollout completed:
  - Prowlarr (Auto-Deploy)
  - MangaDex (Native)
  - Anna's Archive (Stacks)
  - Webtoons (Gallery-dl)
  - Kagane (FlareSolverr)
- Prowlarr post-bootstrap state:
  - LinuxTracker absent.
  - Internet Archive absent.
  - 1337x enabled and tagged for FlareSolverr.
  - Native Prowlarr searches against 1337x returned results for `ubuntu`, `solo leveling`, and `dragon ball super 24`.
- Resolver/search runtime spot checks:
  - `dragon ball super 24` as MANGA resolves to `VOLUME` intent and volume results outrank MangaDex chapter 24.
  - `dragon ball super chapitre 24` as MANGA resolves to `CHAPTER` intent and MangaDex chapter 24 scores high.
  - `solo leveling` as WEBTOON returns Webtoons/Asura candidates.
  - `batman 404` as COMIC returns ComicVine candidates, confirming runtime ComicVine secret wiring.
- Controlled acquisition smoke after rollout, with temporary sources and temporary in-cluster provider:
  - `DIRECT_FILE` EPUB: job `6`, `PENDING_REVIEW`, score `100`, delivered file verified.
  - `MANGADEX_CHAPTER` CBZ: job `7`, `PENDING_REVIEW`, score `100`, delivered file verified.
  - `CLI_GALLERY_DL` CBZ: job `8`, `PENDING_REVIEW`, score `100`, delivered file verified.
  - `KAGANE_CHAPTER` CBZ through real FlareSolverr: job `9`, `PENDING_REVIEW`, score `100`, delivered file verified.
  - `TORRENT` EPUB through fake Prowlarr + fake qBittorrent APIs: job `10`, `PENDING_REVIEW`, score `80`, delivered file verified.
- Cleanup after post-rollout smoke:
  - smoke sources: `0`.
  - smoke jobs/searches: `0`.
  - smoke Kubernetes Deployment/Services/ConfigMap: absent.
  - smoke files under `/bookdrop`: absent.
  - DB counts: users=1, books=2, libraries=1, download_sources=5, download_jobs=0.
