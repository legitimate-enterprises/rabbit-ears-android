BREW         := $(shell brew --prefix 2>/dev/null)
export JAVA_HOME    ?= $(BREW)/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
export ANDROID_HOME ?= $(BREW)/share/android-commandlinetools
SDKMANAGER   := $(ANDROID_HOME)/cmdline-tools/latest/bin/sdkmanager
SDK_PACKAGES := "platforms;android-37.2" "build-tools;37.0.0"
GRADLE       := ./gradlew --quiet
GENERATED    := api/src/main/kotlin

# In CI no daemon, here and in the scripts the targets run: each job has a
# machine of its own, which goes when the job does.
ifdef CI
export GRADLE_OPTS := -Dorg.gradle.daemon=false $(GRADLE_OPTS)
endif

.DEFAULT_GOAL := help
.PHONY: help deps server-deps generate spec build test test-unit test-e2e style lint drift format release deploy clean

help: ## Print this target list
	@grep -hE '^[a-z0-9-]+:.*##' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*## "}; {printf "  %-16s %s\n", $$1, $$2}'

# sdkmanager skips packages already installed; yes answers every license.
deps: ## Install a JDK and the Android SDK, generate the API client
	brew list --formula openjdk@21 >/dev/null 2>&1 || brew install openjdk@21
	brew list --cask android-commandlinetools >/dev/null 2>&1 || brew install --cask android-commandlinetools
	yes | $(SDKMANAGER) --sdk_root=$(ANDROID_HOME) --licenses >/dev/null
	$(SDKMANAGER) --sdk_root=$(ANDROID_HOME) $(SDK_PACKAGES)
	$(MAKE) --no-print-directory generate

# Only test-e2e builds and runs a server, so only it installs these.
server-deps: ## Install Go and ffmpeg, which build and run the server for test-e2e
	command -v go >/dev/null || brew install go
	command -v ffmpeg >/dev/null || brew install ffmpeg

# The file's info.version is the server release this app is built against.
spec: ## Replace openapi.yaml with the one from server release VERSION, and regenerate (make spec VERSION=0.20.0)
	@[ -n "$(VERSION)" ] || { echo "usage: make spec VERSION=<server version>" >&2; exit 64; }
	curl --fail --silent --show-error --location --output openapi.yaml.new \
		"https://raw.githubusercontent.com/legitimate-enterprises/rabbit-ears/v$(VERSION)/server/api/openapi.yaml"
	mv openapi.yaml.new openapi.yaml
	$(MAKE) --no-print-directory generate

generate: ## Regenerate api/src/main/kotlin from openapi.yaml
	$(GRADLE) :api:openApiGenerate
	rm -rf $(GENERATED)
	cp -R api/build/openapi/src/main/kotlin $(GENERATED)

drift: ## Fail if the generated API client is stale against the spec
	@$(GRADLE) :api:openApiGenerate
	@diff -r -q api/build/openapi/src/main/kotlin $(GENERATED) >/dev/null || \
		{ echo "$(GENERATED) is stale; run make generate" >&2; exit 1; }

build: ## Unsigned debug APK in app/build/outputs/apk/debug
	$(GRADLE) :app:assembleDebug

test: style lint build test-unit test-e2e ## Every gate CI enforces

test-unit: ## JVM unit tests, no emulator; the release script against fakes of Gradle and Google
	$(GRADLE) :app:testDebugUnitTest --rerun
	scripts/release-test.sh

test-e2e: server-deps ## A movie downloaded from a throwaway server at openapi.yaml's version, resumed and decoded
	scripts/downloads-e2e.sh

style: ## ktfmt in checking mode
	$(GRADLE) :app:ktfmtCheck

format: ## ktfmt in place
	$(GRADLE) :app:ktfmtFormat

lint: drift ## Android lint and API client drift
	$(GRADLE) :app:lintDebug

release: ## CI only: the App Bundle signed with the upload key, sent to Google Play's Internal testing track
	scripts/release.sh

# There is no local publish path.
deploy: ## Tag origin/main v<version> once CI is green there, and push the tag
	scripts/cut-release.sh --yes

clean: ## Remove build products, the server's source and Gradle's project cache
	rm -rf build app/build api/build .gradle .kotlin
