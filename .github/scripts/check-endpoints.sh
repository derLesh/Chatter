#!/usr/bin/env bash
#
# Asks every address outside Twitch that Chatter depends on whether it is still there, and whether
# it still answers with the shape the app reads.
#
# This exists because two of them had quietly died: 7TV retired the cosmetics endpoint and the
# supporter list moved behind a private repository, and both failures were a line in logcat that
# nobody was looking at. A provider going away is not something a unit test can catch — it is not
# our code that changed — so it is checked here, on a schedule, against the real thing.
#
# The addresses are read out of the source rather than written down again, so a new provider is
# checked from the day it is added and a removed one stops being asked about. An address with a
# placeholder the script does not know, or one whose answer it cannot check the shape of, is a
# failure: that is how it stays honest instead of quietly skipping.
#
# Run it yourself with: .github/scripts/check-endpoints.sh

set -uo pipefail

# A channel that is on all three emote providers, so that a 404 from one of them really does mean
# the address is gone rather than that this streamer never used it. If it ever goes red for a
# channel endpoint only, check whether this streamer dropped the provider and pick another one.
CHANNEL_ID=35933008
CHANNEL=moondye7

SOURCES=(
  app/src/main/java/dev/chatter/app/net/ThirdPartyApis.kt
)

failures=0

fail() {
  echo "FAIL  $1"
  echo "      $2"
  failures=$((failures + 1))
}

# The piece of the answer the app reads, for every address it asks. Anything not named here is a
# failure rather than a pass: an address whose answer nobody checks is how this started.
expected_in() {
  case "$1" in
    *betterttv.net/3/cached/emotes/global*) echo '"code"' ;;
    *betterttv.net/3/cached/users/twitch/*) echo '"channelEmotes"' ;;
    *frankerfacez.com/v1/set/global*) echo '"default_sets"' ;;
    *frankerfacez.com/v1/room/id/*) echo '"sets"' ;;
    *7tv.io/v3/emote-sets/global*) echo '"emotes"' ;;
    *7tv.io/v3/users/twitch/*) echo '"emote_set"' ;;
    *api.chatterino.com/badges*) echo '"badges"' ;;
    *supporters.json*) echo '"supporters"' ;;
    *recent-messages*) echo '"messages"' ;;
    *) echo "" ;;
  esac
}

check_url() {
  local url="$1"
  local expected
  expected="$(expected_in "$url")"
  if [ -z "$expected" ]; then
    fail "$url" "the script does not know what this answer should contain — teach it in expected_in"
    return
  fi

  local body status
  # Three tries: a provider having one bad second should not wake anybody up.
  body="$(curl --silent --show-error --location --retry 3 --retry-delay 5 --max-time 30 \
    --write-out '\n%{http_code}' "$url" 2>/dev/null)"
  status="$(printf '%s' "$body" | tail -n 1)"
  body="$(printf '%s' "$body" | sed '$d')"

  if [ "$status" != "200" ]; then
    fail "$url" "answered $status"
    return
  fi
  if ! printf '%s' "$body" | grep -q -- "$expected"; then
    fail "$url" "answered 200 but without $expected — the shape the app reads has changed"
    return
  fi
  echo "ok    $url"
}

# The 7TV EventAPI, which is a socket rather than a request. Its bridge speaks server-sent events
# over plain HTTP and says hello to anybody who asks, which is enough to know it is alive.
check_eventapi() {
  local url="https://events.7tv.io/v3@emote_set.update%3Cobject_id=01F74BZYAR00069YQS4JB48G14%3E"
  local greeting
  # The stream never ends, so curl is meant to run into its own time limit; what it said until
  # then is the answer. Read it all first: piping it into grep would end curl early, and an early
  # end of something that was going fine looks like a failure to a shell with pipefail on.
  greeting="$(curl --silent --no-buffer --max-time 10 "$url" 2>/dev/null || true)"
  if printf "%s" "$greeting" | grep -q "event: hello"; then
    echo "ok    https://events.7tv.io/v3 (event stream)"
  else
    fail "https://events.7tv.io/v3" "no hello from the event stream — live emote and badge updates are down"
  fi
}

for source in "${SOURCES[@]}"; do
  if [ ! -f "$source" ]; then
    fail "$source" "not there any more — the script is looking in the wrong place"
    continue
  fi
  while read -r url; do
    [ -z "$url" ] && continue
    url="${url//\$channelId/$CHANNEL_ID}"
    url="${url//\$channel/$CHANNEL}"
    url="${url//\$limit/1}"
    case "$url" in
      *'$'*) fail "$url" "there is a placeholder in here the script does not know" ; continue ;;
    esac
    check_url "$url"
  done < <(grep -o 'https://[^"]*' "$source" | sort -u)
done

check_eventapi

echo
if [ "$failures" -gt 0 ]; then
  echo "$failures of the addresses Chatter depends on are not answering the way it expects."
  exit 1
fi
echo "Everything Chatter depends on is answering."
