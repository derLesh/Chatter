# Tests on the phone

What runs on the connected phone and not in CI: the message benchmark, the screen tests and the
baseline profile. [power.md](power.md) covers what Chatter costs in battery.

## What a message costs

`MessageCostBenchmark` in `app/src/androidTest` measures what one message costs on its way in —
parsing, building, the mute list, the rules, the buffer — fed a busy channel's worth of made-up
lines by `BusyChannel`. Run it before and after a change to `irc/` or `chat/`:

```sh
./gradlew :app:connectedMicrobenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=me.lesh.chatter.benchmark
```

It runs against the `microbenchmark` build type: the debug build, not debuggable (a debuggable app
runs unoptimized and would measure nothing a user runs), with an application id of its own,
because a connected test uninstalls what it tested afterwards.

## Screen tests

The tests in `app/src/androidTest/.../flows` check that the real activity, recreated, comes back on
the same screen and page. Run them after a change to the navigation or to what a screen keeps:

```sh
./gradlew :app:connectedMicrobenchmarkAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=me.lesh.chatter.flows
```

They read as a guest without channels, so they need no login and no message arrives. They share
the `microbenchmark` build with the benchmark, so both commands name their package. What is
written, sent and restored is tested on the JVM instead, through `Composer` and a sender the test
controls (`ComposerFlowTest`); a new composer flow goes there.

## The baseline profile

The release carries two lists of what ART compiles ahead of time:

- `app/src/release/generated/` is what `./gradlew :app:generateReleaseBaselineProfile` records on
  the connected phone: the login screen of a fresh install, the start into the chat and a busy
  channel being read and scrolled.
- `app/src/main/baseline-prof.txt` is written by hand and names whole packages — the message path
  that runs all night in the background and the chat drawing — so it does not go stale when a
  method is renamed. A new hot package goes into this file.

The journeys read a busy channel as a guest, so they need no Twitch login, and the phone's own
Chatter is never touched: the builds the profile is recorded and measured on are an app of their
own (`me.lesh.chatter.profiling`), and the only ones that can be told from outside to read a
channel. `-Pandroid.testInstrumentationRunnerArguments.chatterChannel=<login>` picks another
channel when the default is offline — a chat with nothing arriving profiles next to nothing.

Generate it again before a release that changed what the start or the chat runs through: a new
screen on the way, a new kind of message, a change to the list. Then measure what it is worth and
put what `ChatBenchmark` says about the cold start and the chat frames, with the profile and
without, into the commit:

```sh
./gradlew :app:generateReleaseBaselineProfile
./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest
./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=me.lesh.chatter.baselineprofile.PowerBenchmark   # per power rail; see power.md
```
