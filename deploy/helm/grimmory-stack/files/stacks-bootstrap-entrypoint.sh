#!/bin/sh
set -eu

CONFIG_FILE="${STACKS_CONFIG_FILE:-/opt/stacks/config/config.yaml}"
FLARESOLVERR_URL="${STACKS_FLARESOLVERR_URL:-${FLARESOLVERR_URL:-${SOLVERR_URL:-http://flaresolverr:8191}}}"
FLARESOLVERR_TIMEOUT="${STACKS_FLARESOLVERR_TIMEOUT:-60}"
INCOMPLETE_PATH="${STACKS_DOWNLOAD_INCOMPLETE_PATH:-/opt/stacks/download/incomplete}"

patch_config() {
  python3 - "$CONFIG_FILE" "$FLARESOLVERR_URL" "$FLARESOLVERR_TIMEOUT" "$INCOMPLETE_PATH" <<'PY'
import pathlib
import re
import sys

path = pathlib.Path(sys.argv[1])
flare_url = sys.argv[2]
flare_timeout = sys.argv[3]
incomplete_path = sys.argv[4]

if not path.exists():
    print(f"[stacks-bootstrap] Config {path} does not exist yet; Stacks will create it on first run.")
    sys.exit(0)

text = path.read_text(encoding="utf-8")

def ensure_section(source: str, section: str) -> str:
    if re.search(rf"(?m)^{re.escape(section)}:\s*$", source):
        return source
    if source and not source.endswith("\n"):
        source += "\n"
    return source + f"\n{section}:\n"

def ensure_nested(source: str, section: str, key: str, value: str) -> str:
    source = ensure_section(source, section)
    section_match = re.search(rf"(?ms)^{re.escape(section)}:\s*\n(?P<body>.*?)(?=^[A-Za-z0-9_-]+:\s*$|\Z)", source)
    if not section_match:
        raise RuntimeError(f"Unable to locate section {section}")
    body = section_match.group("body")
    rendered = f"  {key}: {value}"
    if re.search(rf"(?m)^  {re.escape(key)}:\s*.*$", body):
        body = re.sub(rf"(?m)^  {re.escape(key)}:\s*.*$", rendered, body)
    else:
        if body and not body.endswith("\n"):
            body += "\n"
        body += rendered + "\n"
    return source[:section_match.start("body")] + body + source[section_match.end("body"):]

updated = text
updated = ensure_nested(updated, "server", "host", "0.0.0.0")
updated = ensure_nested(updated, "server", "port", "7788")
updated = ensure_nested(updated, "flaresolverr", "enabled", "true")
updated = ensure_nested(updated, "flaresolverr", "url", flare_url)
updated = ensure_nested(updated, "flaresolverr", "timeout", flare_timeout)
updated = ensure_nested(updated, "downloads", "incomplete_folder_path", incomplete_path)

if updated != text:
    path.write_text(updated, encoding="utf-8")
    print(f"[stacks-bootstrap] Patched {path}: listen address, flaresolverr and download paths enforced.")
else:
    print(f"[stacks-bootstrap] {path} already contains the required acquisition settings.")
PY
}

patch_config || echo "[stacks-bootstrap] Config patch failed; starting Stacks with existing config."

exec python3 /opt/stacks/stacks.pex
