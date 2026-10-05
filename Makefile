# Ambient Glow — build shortcuts.  Run `make` (or `make help`) to list targets.

ANDROID_HOME ?= $(HOME)/Android/Sdk
export ANDROID_HOME

GRADLE  := ./gradlew --console=plain
ADB     ?= $(ANDROID_HOME)/platform-tools/adb
SDKMGR  := $(ANDROID_HOME)/cmdline-tools/latest/bin/sdkmanager
PACKAGE := com.example.ambientglow

DEBUG_APK   := app/build/outputs/apk/debug/app-debug.apk
RELEASE_APK := app/build/outputs/apk/release/app-release.apk
LINT_REPORT := app/build/reports/lint-results-debug.txt

SDK_PACKAGES := "platform-tools" "platforms;android-37.0" "build-tools;37.0.0"

.DEFAULT_GOAL := help
.PHONY: help debug release bundle lint test check install install-release launch \
        uninstall devices apks clean stop sdk

help: ## List available targets
	@awk 'BEGIN {FS = ":.*## "} /^[a-zA-Z_-]+:.*## / {printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}' $(MAKEFILE_LIST)

debug: ## Build the debug APK
	$(GRADLE) assembleDebug

release: ## Build the R8-minified release APK (debug-signed)
	$(GRADLE) assembleRelease

bundle: ## Build a release App Bundle (.aab)
	$(GRADLE) bundleRelease

lint: ## Run Android lint and print the summary
	$(GRADLE) lintDebug
	@tail -n 1 $(LINT_REPORT)

test: ## Run the JVM unit tests
	$(GRADLE) testDebugUnitTest

check: lint test debug release ## Lint + tests + debug + release: the full pre-deploy gate

install: debug ## Build and install the debug APK on the connected device
	$(ADB) install -r $(DEBUG_APK)

install-release: release ## Build and install the release APK on the connected device
	$(ADB) install -r $(RELEASE_APK)

launch: ## Open the dashboard on the connected device
	$(ADB) shell am start -n $(PACKAGE)/.MainActivity

uninstall: ## Remove the app from the connected device
	$(ADB) uninstall $(PACKAGE)

devices: ## List devices visible to adb
	$(ADB) devices -l

apks: ## Show built APKs and their sizes
	@ls -lh $(DEBUG_APK) $(RELEASE_APK) 2>/dev/null || echo "No APKs yet. Run 'make debug' or 'make release'."

clean: ## Delete build outputs
	$(GRADLE) clean

stop: ## Stop running Gradle daemons
	$(GRADLE) --stop

sdk: ## Install the Android SDK packages this project needs (accepts licenses)
	yes | $(SDKMGR) --licenses > /dev/null
	$(SDKMGR) $(SDK_PACKAGES)
