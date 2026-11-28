#!/usr/bin/env bash
set -euo pipefail

sha=${1:?Usage: deploy-staging.sh SHA}
[[ "$sha" =~ ^[0-9a-f]{40}$ ]]
root=/srv/greenmart
repo="$root/repo"
env_file="$root/shared/.env"
test -r "$env_file"

exec 9>"$root/shared/deploy.lock"
flock 9
git -C "$repo" fetch --no-tags origin develop
if [[ "$(git -C "$repo" rev-parse FETCH_HEAD)" != "$sha" ]]; then
  printf 'Skipping superseded develop commit %s\n' "$sha"
  exit 0
fi
release="$root/releases/$sha"
if [[ ! -d "$release" ]]; then
  git -C "$repo" worktree add --detach "$release" "$sha"
fi

cd "$release"
ln -sfn "$env_file" .env
# SHA-specific images; Compose project name keeps the persistent staging volumes.
export IMAGE_TAG="staging-$sha"
docker compose -p greenmart-staging --env-file "$env_file" -f docker-compose.yml up -d --build --wait --wait-timeout 240
curl --fail --silent --show-error --max-time 15 http://127.0.0.1:8080/actuator/health/readiness >/dev/null
printf 'Staging healthy at %s\n' "$sha"
