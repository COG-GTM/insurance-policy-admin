#!/usr/bin/env bash
# One-shot pas-batch CLI against the local Compose stack (used as PAS_SMOKE_BATCH_CMD by the
# cutover rehearsal). Example: batch-cli.sh --pas.batch.premium.cli.run=true
set -euo pipefail
. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
ensure_env
batch_cli "$@"
