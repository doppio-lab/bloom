#!/usr/bin/env bash
# Nightly database dump, encrypted and sent off the VPS with restic. Reads /opt/bloom/restic.env:
#   RESTIC_REPOSITORY, RESTIC_PASSWORD and the backend credentials (AWS_ACCESS_KEY_ID / AWS_SECRET_ACCESS_KEY
#   for S3-compatible storage, or an sftp: repository for a storage box).
set -euo pipefail
cd "$(dirname "$0")"
set -a; source ./restic.env; set +a

docker compose -f docker-compose.prod.yml exec -T db pg_dump -U "${DB_USER:-bloom}" -Fc bloom \
  | restic backup --stdin --stdin-filename bloom.dump --tag bloom

restic forget --tag bloom --keep-daily 14 --keep-weekly 8 --prune
