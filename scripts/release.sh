#!/usr/bin/env bash
# Builds the release App Bundle signed with the upload key and sends it to
# Google Play's Internal testing track; promotion to closed testing and
# production is done by hand in Play Console. CI only: it is what a release
# job for an `android-v<versionCode>` tag runs, with these in its environment:
#
#   ANDROID_UPLOAD_KEYSTORE           the upload keystore, PKCS12, base64; key alias "upload"
#   ANDROID_UPLOAD_KEYSTORE_PASSWORD  its password, which is also the key's
#   GOOGLE_PLAY_SERVICE_ACCOUNT_KEY   the service account's JSON key, base64
#   RUNNER_TEMP                       a directory the job's runner empties at its end
#
# Play takes a versionCode once only, so a run that committed cannot be run
# again; the next release is the next versionCode.
set -euo pipefail
cd "$(dirname "$0")/.."

die() { echo "error: $*" >&2; exit 1; }

[ -n "${CI:-}" ] || die "releases are produced by CI only; cut one with: make deploy"
missing=""
for var in ANDROID_UPLOAD_KEYSTORE ANDROID_UPLOAD_KEYSTORE_PASSWORD GOOGLE_PLAY_SERVICE_ACCOUNT_KEY; do
    [ -n "${!var:-}" ] || missing="$missing $var"
done
[ -z "$missing" ] || die "repository secrets not set:$missing"
[ -n "${RUNNER_TEMP:-}" ] || die "RUNNER_TEMP is not set; this runs only in a GitHub Actions job"

# Play knows the app by its applicationId, fixed for good by the first
# upload. "qa" is the Play Developer API's name for Internal testing.
package=enterprises.legitimate.rabbitears
track=qa
bundle=app/build/outputs/bundle/release/app-release.aab
edits="https://androidpublisher.googleapis.com/androidpublisher/v3/applications/$package/edits"
uploads="https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/$package/edits"

b64url() { openssl base64 -A | tr '/+' '_-' | tr -d '='; }

# call <curl args>... <url>: the response body, or Google's error on stderr
# and a failure. Credentials go to curl on file descriptors, never in its
# arguments, which any process on the runner can list.
call() {
    local out
    out="$(curl --silent --show-error --connect-timeout 30 --max-time 1800 --write-out '\n%{http_code}' "$@")"
    if [[ "${out##*$'\n'}" != 2?? ]]; then
        echo "error: ${*: -1} answered HTTP ${out##*$'\n'}: ${out%$'\n'*}" >&2
        return 1
    fi
    printf '%s\n' "${out%$'\n'*}"
}
auth() { printf 'Authorization: Bearer %s\n' "$token"; }

# The access token comes before the build: a bad key stops the run before it builds.
account="$(printf '%s' "$GOOGLE_PLAY_SERVICE_ACCOUNT_KEY" | base64 --decode 2>/dev/null)" || true
email="$(printf '%s' "$account" | jq -er .client_email 2>/dev/null)" ||
    die "GOOGLE_PLAY_SERVICE_ACCOUNT_KEY is not a base64 service account JSON key"
now="$(date +%s)"
claims="$(jq -nc --arg iss "$email" --argjson iat "$now" '{iss: $iss,
    scope: "https://www.googleapis.com/auth/androidpublisher",
    aud: "https://oauth2.googleapis.com/token", iat: $iat, exp: ($iat + 3600)}')"
assertion="$(printf '{"alg":"RS256","typ":"JWT"}' | b64url).$(printf '%s' "$claims" | b64url)"
signature="$(printf '%s' "$assertion" |
    openssl dgst -sha256 -sign <(printf '%s' "$account" | jq -r .private_key) | b64url)"
token="$(call --data-urlencode grant_type=urn:ietf:params:oauth:grant-type:jwt-bearer \
    --data-urlencode assertion@<(printf '%s' "$assertion.$signature") \
    https://oauth2.googleapis.com/token | jq -er .access_token)"

# The keystore is removed when the script exits, however it exits.
keystore="$RUNNER_TEMP/upload.keystore"
trap 'rm -f "$keystore"' EXIT
(umask 077 && printf '%s' "$ANDROID_UPLOAD_KEYSTORE" | base64 --decode > "$keystore") ||
    die "ANDROID_UPLOAD_KEYSTORE is not base64"
# No daemon or configuration cache entry outlives the build that read the password.
ANDROID_UPLOAD_KEYSTORE_FILE="$keystore" \
    ./gradlew --quiet --no-daemon --no-configuration-cache :app:bundleRelease
rm -f "$keystore"

edit="$(call -H @<(auth) --json '{}' "$edits" | jq -er .id)"
code="$(call -H @<(auth) -H 'Content-Type: application/octet-stream' --data-binary @"$bundle" \
    "$uploads/$edit/bundles?uploadType=media" | jq -er .versionCode)"
call -H @<(auth) -X PUT \
    --json "$(jq -nc --arg code "$code" '{releases: [{versionCodes: [$code], status: "completed"}]}')" \
    "$edits/$edit/tracks/$track" >/dev/null
# A release already in review, such as a production promotion made in Play
# Console, stays in review: the commit fails instead of cancelling it.
call -H @<(auth) --data '' "$edits/$edit:commit?changesInReviewBehavior=ERROR_IF_IN_REVIEW" >/dev/null
echo "versionCode $code is on Google Play's Internal testing track"
