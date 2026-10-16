.PHONY: test apk check docker-build

# The Gradle wrapper is the single source of truth for the build tool. Use --offline so
# accidental network access is visible; Docker/CI populate the cache before this target.
test:
	./gradlew :engine:test --offline --no-daemon --stacktrace

apk:
	./gradlew :app:assembleDebug --offline --no-daemon --stacktrace

check: test apk

docker-build:
	docker build --tag opd-assistant:local .
