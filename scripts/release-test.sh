#!/usr/bin/env bash
# Runs release.sh in a scratch copy of the stack against fakes of gradlew
# and curl: its CI and secret guards, the signed assertion Google verifies,
# the order and shape of the Play Developer API calls, that no credential
# reaches a process's arguments, and that the keystore does not outlive it.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

fail() { echo "FAIL: $*" >&2; exit 1; }

mkdir -p "$tmp/stack/scripts" "$tmp/bin" "$tmp/runner"
cp "$here/release.sh" "$tmp/stack/scripts/"
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out "$tmp/key.pem" 2>/dev/null
openssl pkey -in "$tmp/key.pem" -pubout -out "$tmp/pub.pem"

cat > "$tmp/stack/gradlew" <<'EOF'
#!/usr/bin/env bash
[ "$(cat "$ANDROID_UPLOAD_KEYSTORE_FILE")" = keystore-bytes ] || { echo "gradlew: wrong keystore" >&2; exit 1; }
[ "$(stat -f %Lp "$ANDROID_UPLOAD_KEYSTORE_FILE")" = 600 ] || { echo "gradlew: keystore readable by others" >&2; exit 1; }
[ -z "${FAKE_GRADLE_FAIL:-}" ] || { echo "gradlew: build failed" >&2; exit 1; }
mkdir -p app/build/outputs/bundle/release
echo bundle-bytes > app/build/outputs/bundle/release/app-release.aab
EOF

# Logs "<method> <url> <headers read from files> <body>" and answers as
# Google would, refusing a call that has no timeouts; FAKE_FAIL names a URL
# fragment to refuse.
cat > "$tmp/bin/curl" <<'EOF'
#!/usr/bin/env bash
case " $* " in *" --connect-timeout 30 --max-time 1800 "*) ;; *) echo "curl without timeouts: $*" >&2; exit 98 ;; esac
for a in "$@"; do
    case "$a" in *fake-access-token* | *PRIVATE* | *keystore-bytes* | *eyJ*) echo "credential in curl's arguments: $a" >&2; exit 99 ;; esac
done
method=GET url="" headers="" body=""
while [ $# -gt 0 ]; do
    case "$1" in
        -X) method="$2"; shift ;;
        -H) case "$2" in @*) headers="$headers$(cat "${2#@}")" ;; *) headers="$headers$2" ;; esac; shift ;;
        --json | --data | --data-binary) [ "$method" = GET ] && method=POST; body="$2"; shift ;;
        --data-urlencode) method=POST
            case "$2" in assertion@*) cat "${2#assertion@}" > "$FAKE_DIR/assertion" ;; esac; shift ;;
        --write-out | --connect-timeout | --max-time) shift ;;
        -*) ;;
        *) url="$1" ;;
    esac
    shift
done
echo "$method $url${headers:+ $headers}${body:+ $body}" >> "$FAKE_DIR/log"
case "$url" in
    *"${FAKE_FAIL:-none}"*) code=403 answer='{"error":{"message":"The caller does not have permission"}}' ;;
    */token) code=200 answer='{"access_token":"fake-access-token"}' ;;
    */edits) code=200 answer='{"id":"e1"}' ;;
    */bundles*) code=200 answer='{"versionCode":7}' ;;
    *) code=200 answer='{}' ;;
esac
printf '%s\n%s' "$answer" "$code"
EOF
chmod +x "$tmp/stack/gradlew" "$tmp/bin/curl"

account="$(jq -nc --rawfile key "$tmp/key.pem" '{client_email: "ci@example.iam.gserviceaccount.com", private_key: $key}')"
secrets=(
    ANDROID_UPLOAD_KEYSTORE="$(printf keystore-bytes | base64)"
    ANDROID_UPLOAD_KEYSTORE_PASSWORD=pass
    GOOGLE_PLAY_SERVICE_ACCOUNT_KEY="$(printf '%s' "$account" | base64)"
)
release() {
    rm -f "$tmp/log"
    env -u CI -u ANDROID_UPLOAD_KEYSTORE -u ANDROID_UPLOAD_KEYSTORE_PASSWORD -u GOOGLE_PLAY_SERVICE_ACCOUNT_KEY \
        PATH="$tmp/bin:$PATH" FAKE_DIR="$tmp" RUNNER_TEMP="$tmp/runner" "$@" "$tmp/stack/scripts/release.sh" 2>&1
}

out="$(release "${secrets[@]}")" && fail "ran outside CI"
[[ "$out" == *"CI only"* ]] || fail "outside CI: $out"

for name in ANDROID_UPLOAD_KEYSTORE ANDROID_UPLOAD_KEYSTORE_PASSWORD GOOGLE_PLAY_SERVICE_ACCOUNT_KEY; do
    out="$(release CI=true "${secrets[@]}" "$name=")" && fail "ran without $name"
    [[ "$out" == *"repository secrets not set: $name" ]] || fail "without $name: $out"
    [ ! -e "$tmp/log" ] || fail "without $name, Google was called"
done
out="$(release CI=true)" && fail "ran without secrets"
[[ "$out" == *"not set: ANDROID_UPLOAD_KEYSTORE ANDROID_UPLOAD_KEYSTORE_PASSWORD GOOGLE_PLAY_SERVICE_ACCOUNT_KEY" ]] ||
    fail "without secrets: $out"

out="$(release CI=true "${secrets[@]}" GOOGLE_PLAY_SERVICE_ACCOUNT_KEY=bm90IGpzb24=)" && fail "ran with a bad key"
[[ "$out" == *"not a base64 service account JSON key"* ]] || fail "bad key: $out"

out="$(release CI=true "${secrets[@]}")" || fail "release: $out"
[[ "$out" == *"versionCode 7 is on Google Play's Internal testing track"* ]] || fail "release said: $out"
[ ! -e "$tmp/runner/upload.keystore" ] || fail "the keystore outlived the release"

api=https://androidpublisher.googleapis.com
app=applications/enterprises.legitimate.rabbitears/edits
auth="Authorization: Bearer fake-access-token"
expected="POST https://oauth2.googleapis.com/token
POST $api/androidpublisher/v3/$app $auth {}
POST $api/upload/androidpublisher/v3/$app/e1/bundles?uploadType=media ${auth}Content-Type: application/octet-stream @app/build/outputs/bundle/release/app-release.aab
PUT $api/androidpublisher/v3/$app/e1/tracks/qa $auth {\"releases\":[{\"versionCodes\":[\"7\"],\"status\":\"completed\"}]}
POST $api/androidpublisher/v3/$app/e1:commit?changesInReviewBehavior=ERROR_IF_IN_REVIEW $auth"
[ "$(cat "$tmp/log")" = "$expected" ] || fail "requests were:
$(cat "$tmp/log")"

# The assertion Google checks: RS256 over header.claims by the account's key.
b64url_decode() {
    local s
    s="$(tr '_-' '/+')"
    while [ $((${#s} % 4)) -ne 0 ]; do s="$s="; done
    printf '%s' "$s" | base64 --decode
}
IFS=. read -r header claims signature <<< "$(cat "$tmp/assertion")"
printf '%s' "$signature" | b64url_decode > "$tmp/signature"
printf '%s' "$header.$claims" | openssl dgst -sha256 -verify "$tmp/pub.pem" -signature "$tmp/signature" >/dev/null ||
    fail "the assertion's signature does not verify"
[ "$(printf '%s' "$header" | b64url_decode | jq -c .)" = '{"alg":"RS256","typ":"JWT"}' ] || fail "header"
printf '%s' "$claims" | b64url_decode | jq -e '.iss == "ci@example.iam.gserviceaccount.com"
    and .scope == "https://www.googleapis.com/auth/androidpublisher"
    and .aud == "https://oauth2.googleapis.com/token" and .exp - .iat == 3600' >/dev/null ||
    fail "claims: $(printf '%s' "$claims" | b64url_decode)"

out="$(release CI=true "${secrets[@]}" FAKE_FAIL=:commit)" && fail "a refused commit passed"
[[ "$out" == *"answered HTTP 403: "*"The caller does not have permission"* ]] || fail "refused commit: $out"
[ ! -e "$tmp/runner/upload.keystore" ] || fail "the keystore outlived a failed release"

out="$(release CI=true "${secrets[@]}" FAKE_GRADLE_FAIL=1)" && fail "a failed build passed"
[[ "$out" == *"gradlew: build failed"* ]] || fail "failed build: $out"
[ ! -e "$tmp/runner/upload.keystore" ] || fail "the keystore outlived a failed build"
[ "$(cat "$tmp/log")" = "POST https://oauth2.googleapis.com/token" ] || fail "a failed build reached Google: $(cat "$tmp/log")"

echo "release.sh: all checks passed"
