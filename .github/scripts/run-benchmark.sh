#!/bin/sh
# Runs the message benchmark of the checkout in $1 on the device that is attached, and copies what
# it measured to $2 (an absolute path). A checkout from before the benchmark existed has nothing to
# run; it says so and leaves $2 unwritten, which the summary then reads as "nothing to compare".
#
#     .github/scripts/run-benchmark.sh branch "$PWD/branch.json"
set -e

cd "$1"
if ! grep -q 'testBuildType = "microbenchmark"' app/build.gradle.kts; then
  echo "$1 has no message benchmark to run."
  exit 0
fi

./gradlew :app:connectedMicrobenchmarkAndroidTest

result=$(find app/build/outputs/connected_android_test_additional_output -name '*benchmarkData.json' | head -n 1)
if [ -z "$result" ]; then
  echo "The benchmark ran, but left no benchmarkData.json behind." >&2
  exit 1
fi
cp "$result" "$2"
