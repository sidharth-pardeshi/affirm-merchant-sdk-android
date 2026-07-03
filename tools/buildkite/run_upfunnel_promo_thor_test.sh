#!/usr/bin/env bash
set -euo pipefail

buildkite_metadata() {
  if command -v buildkite-agent >/dev/null 2>&1; then
    buildkite-agent meta-data get "$1" 2>/dev/null || true
  fi
}

if [[ -z "${AFFIRM_PROMO_BASE_URL:-}" ]]; then
  THOR_ID="$(buildkite_metadata "thor-id-us-live")"
  if [[ -n "$THOR_ID" ]]; then
    AFFIRM_PROMO_BASE_URL="https://${THOR_ID}.affirm-thor.com"
  fi
fi

AFFIRM_PUBLIC_KEY="${AFFIRM_PUBLIC_KEY:-$(buildkite_metadata "upfunnel-sdk-promo-messaging-public-key")}"

AFFIRM_PROMO_EXTERNAL_ID="${AFFIRM_PROMO_EXTERNAL_ID:-test_external_id}"
AFFIRM_EXPECTED_PROMO_TEXT="${AFFIRM_EXPECTED_PROMO_TEXT:-$(buildkite_metadata "upfunnel-sdk-promo-messaging-expected-ala")}"
AFFIRM_EXPECTED_PROMO_TEXT="${AFFIRM_EXPECTED_PROMO_TEXT:-Affirm}"
AFFIRM_COUNTRY_CODE="${AFFIRM_COUNTRY_CODE:-USA}"
AFFIRM_LOCALE="${AFFIRM_LOCALE:-en_US}"
ANDROID_TEST_RUNNER="${ANDROID_TEST_RUNNER:-firebase}"
FIREBASE_PROJECT="${FIREBASE_PROJECT:-firebase-affirm}"
FIREBASE_TEST_LOG="${FIREBASE_TEST_LOG:-firebase-test-lab.log}"

: "${AFFIRM_PROMO_BASE_URL:?AFFIRM_PROMO_BASE_URL must be set, e.g. https://<thor-id>.affirm-thor.com}"
: "${AFFIRM_PUBLIC_KEY:?AFFIRM_PUBLIC_KEY must be set to the Thor merchant public key}"

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT_DIR"

if ! java -version 2>&1 | grep -q 'version "1\.8\.'; then
  JDK8_DIR="${JDK8_DIR:-$ROOT_DIR/.buildkite-jdk8}"
  if [[ ! -x "$JDK8_DIR/bin/java" ]]; then
    mkdir -p "$JDK8_DIR"
    jdk8_archive="$(mktemp)"
    jdk8_url="${JDK8_URL:-https://api.adoptium.net/v3/binary/latest/8/ga/linux/x64/jdk/hotspot/normal/eclipse?project=jdk}"
    if command -v curl >/dev/null 2>&1; then
      curl -fsSL "$jdk8_url" -o "$jdk8_archive"
    else
      wget -qO "$jdk8_archive" "$jdk8_url"
    fi
    tar -xzf "$jdk8_archive" -C "$JDK8_DIR" --strip-components=1
    rm -f "$jdk8_archive"
  fi
  export JAVA_HOME="$JDK8_DIR"
  export PATH="$JAVA_HOME/bin:$PATH"
fi

java -version
./gradlew :samples-java:assembleDebug :samples-java:assembleDebugAndroidTest

if [[ "$ANDROID_TEST_RUNNER" == "connected" ]]; then
  ./gradlew :samples-java:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class=com.affirm.android.PromoMessagingThorEspressoTest \
    -Pandroid.testInstrumentationRunnerArguments.affirmPromoBaseUrl="$AFFIRM_PROMO_BASE_URL" \
    -Pandroid.testInstrumentationRunnerArguments.affirmPublicKey="$AFFIRM_PUBLIC_KEY" \
    -Pandroid.testInstrumentationRunnerArguments.affirmPromoExternalId="$AFFIRM_PROMO_EXTERNAL_ID" \
    -Pandroid.testInstrumentationRunnerArguments.affirmExpectedPromoText="$AFFIRM_EXPECTED_PROMO_TEXT" \
    -Pandroid.testInstrumentationRunnerArguments.affirmCountryCode="$AFFIRM_COUNTRY_CODE" \
    -Pandroid.testInstrumentationRunnerArguments.affirmLocale="$AFFIRM_LOCALE"
  exit 0
fi

if [[ "$ANDROID_TEST_RUNNER" != "firebase" ]]; then
  echo "Unknown ANDROID_TEST_RUNNER '$ANDROID_TEST_RUNNER'. Expected 'firebase' or 'connected'." >&2
  exit 2
fi

if ! command -v gcloud >/dev/null 2>&1; then
  if command -v apt-get >/dev/null 2>&1; then
    apt-get update
    apt-get install -y ca-certificates curl gnupg
    install -d -m 0755 /usr/share/keyrings
    curl -fsSL https://packages.cloud.google.com/apt/doc/apt-key.gpg \
      | gpg --dearmor -o /usr/share/keyrings/cloud.google.gpg
    echo "deb [signed-by=/usr/share/keyrings/cloud.google.gpg] https://packages.cloud.google.com/apt cloud-sdk main" \
      > /etc/apt/sources.list.d/google-cloud-sdk.list
    apt-get update
    apt-get install -y google-cloud-cli
  else
    echo "gcloud is required for Firebase Test Lab, and this image does not support apt-get installation." >&2
    exit 2
  fi
fi

if [[ -n "${FIREBASE_SERVICE_ACCOUNT:-}" ]]; then
  firebase_credentials="$(mktemp)"
  firebase_credentials_decoded="${firebase_credentials}.decoded"
  printf "%s" "$FIREBASE_SERVICE_ACCOUNT" > "$firebase_credentials"
  if ! grep -q '"type"[[:space:]]*:[[:space:]]*"service_account"' "$firebase_credentials"; then
    if printf "%s" "$FIREBASE_SERVICE_ACCOUNT" | base64 -d > "$firebase_credentials_decoded" 2>/dev/null \
      && grep -q '"type"[[:space:]]*:[[:space:]]*"service_account"' "$firebase_credentials_decoded"; then
      mv "$firebase_credentials_decoded" "$firebase_credentials"
    else
      rm -f "$firebase_credentials_decoded"
      echo "FIREBASE_SERVICE_ACCOUNT must be a Google service-account JSON key, either raw JSON or base64-encoded JSON." >&2
      exit 2
    fi
  fi
  gcloud auth activate-service-account --key-file="$firebase_credentials"
fi

gcloud config set project "$FIREBASE_PROJECT"

gcloud firebase test android run \
  --type instrumentation \
  --app samples-java/build/outputs/apk/debug/samples-java-debug.apk \
  --test samples-java/build/outputs/apk/androidTest/debug/samples-java-debug-androidTest.apk \
  --environment-variables "class=com.affirm.android.PromoMessagingThorEspressoTest,affirmPromoBaseUrl=${AFFIRM_PROMO_BASE_URL},affirmPublicKey=${AFFIRM_PUBLIC_KEY},affirmPromoExternalId=${AFFIRM_PROMO_EXTERNAL_ID},affirmExpectedPromoText=${AFFIRM_EXPECTED_PROMO_TEXT},affirmCountryCode=${AFFIRM_COUNTRY_CODE},affirmLocale=${AFFIRM_LOCALE}" \
  --directories-to-pull /sdcard/Android/data/com.affirm.samples/files/upfunnel-promo-screenshots \
  --results-bucket "${FIREBASE_RESULTS_BUCKET:-firebase-affirm-android}" \
  --results-dir "upfunnel-promo-sdk-${BUILDKITE_BUILD_NUMBER:-local}-${BUILDKITE_JOB_ID:-manual}" \
  --timeout 10m \
  2>&1 | tee "$FIREBASE_TEST_LOG"
