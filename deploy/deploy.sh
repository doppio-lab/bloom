#!/usr/bin/env bash
# Pulls an image and restarts the stack. Usage, from /opt/bloom: ./deploy.sh <tag>   (tag = commit sha on main)
set -euo pipefail
cd "$(dirname "$0")"

tag="${1:-}"
if [[ -n "$tag" ]]; then
  # Keep the tag in .env so a plain "docker compose up" later restarts the same version
  sed -i.bak "s/^BLOOM_TAG=.*/BLOOM_TAG=${tag}/" .env && rm -f .env.bak
fi

compose=(docker compose -f docker-compose.prod.yml)
"${compose[@]}" pull
"${compose[@]}" up -d --remove-orphans

echo "Waiting for /health..."
for _ in $(seq 1 60); do
  if curl -fsS http://127.0.0.1:8080/health >/dev/null 2>&1; then
    echo "Bloom is up ($(grep '^BLOOM_TAG=' .env | cut -d= -f2))"
    exit 0
  fi
  sleep 2
done
echo "Bloom did not answer within 2 minutes:" >&2
"${compose[@]}" logs --tail 50 app >&2
exit 1
