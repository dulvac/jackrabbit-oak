#!/usr/bin/env bash
# render-mermaid.sh — Extract every ```mermaid block from a markdown file
# and render each one as SVG (and optionally PNG) into a sibling directory.
#
# Tool preference (first available wins):
#   1. mmdc                                   (mermaid-cli installed globally)
#   2. npx @mermaid-js/mermaid-cli            (Node.js + network, no global install)
#   3. docker run minlag/mermaid-cli          (Docker, no Node.js)
# If none are available, print install instructions and exit.
#
# Run from anywhere; paths are resolved relative to the script if no source given.
#
# Usage:
#   render-mermaid.sh                                              # render the default doc
#   render-mermaid.sh path/to/doc.md                               # render a specific doc
#   render-mermaid.sh path/to/doc.md path/to/output-dir            # explicit output
#   render-mermaid.sh path/to/doc.md path/to/output-dir png        # PNG instead of SVG
#   render-mermaid.sh path/to/doc.md path/to/output-dir svg,png    # both formats

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

# Defaults — resolved relative to the repo root.
DEFAULT_SOURCE="$REPO_ROOT/.claude/oak/plans/audit-spi/06-scope-and-flow.md"

SOURCE="${1:-$DEFAULT_SOURCE}"
if [ ! -f "$SOURCE" ]; then
  echo "Error: source markdown not found: $SOURCE" >&2
  exit 1
fi
SOURCE="$(cd "$(dirname "$SOURCE")" && pwd)/$(basename "$SOURCE")"

OUTPUT_DIR="${2:-$(dirname "$SOURCE")/diagrams}"
FORMATS="${3:-svg}"   # comma-separated: svg | png | svg,png

mkdir -p "$OUTPUT_DIR"

# -----------------------------------------------------------------------------
# 1. Detect a working mermaid renderer.
# -----------------------------------------------------------------------------

RENDERER=""
RENDERER_DESC=""
if command -v mmdc >/dev/null 2>&1; then
  RENDERER="mmdc"
  RENDERER_DESC="mmdc (global)"
elif command -v npx >/dev/null 2>&1; then
  RENDERER="npx_mmdc"
  RENDERER_DESC="npx @mermaid-js/mermaid-cli"
elif command -v docker >/dev/null 2>&1; then
  RENDERER="docker"
  RENDERER_DESC="docker run minlag/mermaid-cli"
else
  cat <<'EOF' >&2
Error: no mermaid renderer found. Install one of:

  Global (one-time, ~50 MB):
    npm install -g @mermaid-js/mermaid-cli

  Per-invocation (uses network each call, no global install):
    # nothing to do — make sure 'npx' is on PATH (comes with Node.js)

  Docker (no Node.js needed):
    docker pull minlag/mermaid-cli

Then re-run this script.
EOF
  exit 2
fi

# -----------------------------------------------------------------------------
# 2. Extract every ```mermaid ... ``` block into a separate .mmd file.
#    Numbered 01, 02, … in document order.
# -----------------------------------------------------------------------------

WORK_DIR="$(mktemp -d)"
trap 'rm -rf "$WORK_DIR"' EXIT

awk -v out="$WORK_DIR" '
  /^```mermaid[[:space:]]*$/ { in_block=1; n++; file=sprintf("%s/%02d.mmd", out, n); next }
  /^```[[:space:]]*$/ && in_block { in_block=0; next }
  in_block { print > file }
' "$SOURCE"

shopt -s nullglob
MMD_FILES=("$WORK_DIR"/*.mmd)
shopt -u nullglob

if [ "${#MMD_FILES[@]}" -eq 0 ]; then
  echo "No \`\`\`mermaid blocks found in $SOURCE" >&2
  exit 1
fi

echo "Source:    $SOURCE"
echo "Output:    $OUTPUT_DIR"
echo "Renderer:  $RENDERER_DESC"
echo "Formats:   $FORMATS"
echo "Diagrams:  ${#MMD_FILES[@]}"
echo

# -----------------------------------------------------------------------------
# 3. Render each .mmd in each requested format.
# -----------------------------------------------------------------------------

render_one() {
  local in_file="$1"
  local out_file="$2"
  case "$RENDERER" in
    mmdc)
      mmdc -i "$in_file" -o "$out_file" -b transparent --quiet
      ;;
    npx_mmdc)
      npx --yes -p @mermaid-js/mermaid-cli mmdc -i "$in_file" -o "$out_file" -b transparent --quiet
      ;;
    docker)
      # Mount the work dir read-only and the output dir as the cwd inside the container.
      docker run --rm \
        -v "$(dirname "$in_file"):/in:ro" \
        -v "$(dirname "$out_file"):/out" \
        minlag/mermaid-cli \
        -i "/in/$(basename "$in_file")" -o "/out/$(basename "$out_file")" \
        -b transparent --quiet
      ;;
  esac
}

IFS=',' read -ra FMT_ARRAY <<< "$FORMATS"

idx=0
for mmd in "${MMD_FILES[@]}"; do
  idx=$((idx + 1))
  base="$(basename "$mmd" .mmd)"
  src_stem="$(basename "$SOURCE" .md)"

  for fmt in "${FMT_ARRAY[@]}"; do
    fmt="$(echo "$fmt" | tr '[:upper:]' '[:lower:]' | tr -d '[:space:]')"
    case "$fmt" in
      svg|png|pdf) ;;
      *) echo "Skipping unsupported format: $fmt" >&2 ; continue ;;
    esac
    out="$OUTPUT_DIR/${src_stem}-${base}.${fmt}"
    printf '  [%d/%d] %s ... ' "$idx" "${#MMD_FILES[@]}" "$(basename "$out")"
    if render_one "$mmd" "$out"; then
      echo "ok ($(du -h "$out" | cut -f1))"
    else
      echo "FAILED"
      exit 3
    fi
  done
done

echo
echo "Done. ${#MMD_FILES[@]} diagram(s) rendered to $OUTPUT_DIR/"
