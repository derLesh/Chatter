# What Chatter costs in battery

Two situations matter most: the chat open for hours next to a stream, and a busy channel joined in
the background overnight. This page says how both are measured and holds the numbers so far, so
that a later change has something to be compared with.

## What the first run found

Measured on 2026-10-16 on a Pixel 11 Pro XL (Android 17, build CD1A.260905.001.B1) on Wi-Fi,
Chatter 0.8.0 (177ba52), the `benchmarkRelease` build, reading as a guest.

- **An open chat** costs the phone about **1.2 W** with the screen at half brightness. The display
  takes about 220 mW of it, Wi-Fi 100–200 mW, the CPU about 100 mW and the GPU under 10 mW.
- **None of the three settings makes a difference you can measure.** Still emotes, pure black and
  smooth scrolling each stay inside the spread of the same setting measured three times. Pure
  black saves nothing because Chatter's dark theme is already nearly black.
- **The chat is not drawn 120 times a second.** The display switches between 10 and 120 Hz on its
  own, and Chatter hands it 30–55 frames a second with smooth scrolling on or off.
- **A night in the background** with a busy channel costs about **90 mW** more than the same phone
  without Chatter, 0.7 Wh over eight hours. Nearly all of it is Wi-Fi: the channel sends some 20
  packets a second, so the radio never gets to rest. The CPU rails do not move; the app's own CPU
  time is 25 seconds in 30 minutes.

What that means for the other battery issues:

- **#134** (let the screen turn off when the chat is quiet) is worth doing first. The display alone
  draws about 220 mW, more than anything Chatter does itself, and with the screen off the drawing
  stops as well.
- **#131** (a lower frame rate for a busy chat) is not worth doing on a phone like this one: the
  frame rate is not 120, and the GPU draws under 10 mW. A phone that keeps 120 Hz fixed might tell
  a different story.
- In the background, only less traffic would save anything, and the traffic is the chat itself.

## An open chat: `PowerBenchmark`

`baselineprofile/.../PowerBenchmark.kt` opens a busy channel and leaves it alone for a minute,
three times per variant, and reads the phone's power rails while it does. It needs a phone that
reports its rails, such as a Pixel 6 or newer. It sets the brightness to 128 of 255 for the run
and puts it back afterwards, so that the light in the room does not decide the display's share.

```sh
./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.chatter.app.baselineprofile.PowerBenchmark \
  -Pandroid.testInstrumentationRunnerArguments.chatterChannel=<a busy channel that is live>
```

Battery saver must be off: it turns off animated emotes and smooth scrolling on its own.

The results name every value `…Uw`, but they are mW. Each rail is listed on its own, because the
library sorts the Pixel 11's rails badly: Wi-Fi ends up under "uncategorized" and the CPU leaves
out the DSU. `VSYS_ALL` is larger than all other rails together and is most likely the whole
phone, so the `powerTotalUw` that adds everything up counts much of it twice. Read `VSYS_ALL` for
the whole phone.

The messages are live, so two runs never see the same chat. Compare variants within one run, and
believe only differences that are larger than the spread of a variant.

### Numbers

mW, median of three minutes, smallest and largest in brackets. Channel `zackrawrr`, about 40,000
viewers. All variants dark, with the screen kept on; "defaults" is otherwise the app as installed.

| Rail | defaults | still emotes | pure black | no smooth scrolling |
| --- | --- | --- | --- | --- |
| Whole phone (`VSYS_ALL`) | 1218 (1183–1338) | 1169 (1132–1372) | 1273 (1265–1291) | 1251 (1134–1265) |
| Display | 223 (223–230) | 221 (215–233) | 219 (192–220) | 214 (198–225) |
| Wi-Fi and Bluetooth | 116 (93–179) | 115 (95–203) | 153 (109–169) | 118 (108–124) |
| Modem | 21 (20–21) | 19 (19–20) | 19 (16–20) | 17 (16–17) |
| CPU (three clusters and DSU) | 103 (96–114) | 95 (90–118) | 108 (96–114) | 102 (97–117) |
| GPU | 8 (7–8) | 6 (6–9) | 7 (6–8) | 6 (5–9) |
| Memory | 29 (28–31) | 25 (25–32) | 27 (25–27) | 28 (26–31) |

## A night in the background

Two traces of 30 minutes each with the screen off: one with a busy channel joined, one with that
app stopped. The difference between the two is what the channel costs. The Perfetto config is
`.agents/skills/android-profiler/recording/workflows/perfetto-trace-recording/references/example-configs/chatter_background_power.pftxt`.

A guest gets no background service in a normal build. The profiling builds start it for a guest
too, so they stay connected like a logged-in account; what they lack is whispers and mentions.

```sh
./gradlew :app:installBenchmarkRelease
adb shell am start -n dev.chatter.app.profiling/dev.chatter.app.MainActivity --es profiling_channel <channel>
# Wait until messages arrive, then:
adb shell input keyevent KEYCODE_HOME
adb push chatter_background_power.pftxt /data/misc/perfetto-configs/

# Once with the channel joined, once after `adb shell am force-stop dev.chatter.app.profiling`:
adb shell "dumpsys battery unplug; input keyevent KEYCODE_SLEEP"
adb shell perfetto --txt -c /data/misc/perfetto-configs/chatter_background_power.pftxt \
  -o /data/misc/perfetto-traces/with-chatter.pftrace
adb shell dumpsys battery reset
adb pull /data/misc/perfetto-traces/with-chatter.pftrace
```

`dumpsys battery unplug` makes Android behave as on battery while the cable stays in, which the
trace needs. The USB connection still keeps the phone from sleeping deeply, so the phone's own
draw is far higher than on a real night: about 620 mW without Chatter. The difference between the
two traces holds up; the totals do not. Other apps, the phone's own Chatter among them, run in
both traces and drop out of the difference.

### Queries

For `trace_processor_shell -q <file.sql> <trace>`. Replace the package where it is named.

What each rail drew on average, in mW:

```sql
select t.name as rail,
  (max(c.value) - min(c.value)) / ((max(c.ts) - min(c.ts)) / 1e9) / 1000 as mw
from counter c join counter_track t on c.track_id = t.id
where t.name like 'power.%'
group by t.name order by mw desc;
```

The app's CPU time, how often its threads got the CPU and how often they were woken:

```sql
select sum(s.dur) / 1e9 as cpu_seconds, count(*) as times_on_cpu
from sched s join thread using (utid) join process using (upid)
where process.name = 'dev.chatter.app.profiling';

select count(*) as wakeups
from thread_state ts join thread using (utid) join process using (upid)
where process.name = 'dev.chatter.app.profiling' and ts.state = 'R';
```

Packets per app, and how often the radio could have rested in between — a burst begins after more
than a second without a packet:

```sql
with p as (
  select s.name as app, s.ts, n.packet_length,
    lag(s.ts) over (partition by s.name order by s.ts) as previous
  from __intrinsic_android_network_packets n join slice s using (id))
select app, count(*) as packets, sum(packet_length) / 1024 as kib,
  sum(previous is null or ts - previous > 1e9) as bursts
from p group by app order by packets desc;
```

Wakelocks the app held, by tag:

```sql
select s.name, count(*) as times, sum(s.dur) / 1e9 as seconds
from slice s join track t on s.track_id = t.id
where t.name = 'WakeLocks' and s.name like '%chatter%'
group by s.name;
```

### Numbers

Channel `jynxzi`, about 57,000 viewers, 30 minutes each.

| | with the channel | without | difference |
| --- | --- | --- | --- |
| Whole phone (`VSYS_ALL`), mW | 712 | 620 | +92 |
| Wi-Fi and Bluetooth, mW | 108 | 38 | +70 |
| Modem, mW | 25 | 21 | +4 |
| CPU clusters and DSU, mW | 26 | 26 | 0 |
| Display (always-on display), mW | 49 | 50 | 0 |

| The app in those 30 minutes | |
| --- | --- |
| CPU time | 25 s, most of it on the WebSocket thread (16 s) and in coroutines (8 s) |
| Times its threads were woken | 29,600, about 16 a second |
| Packets | 36,200, 10 MiB |
| Bursts with a second of rest before | 16 |
| Wakelocks with its name in the tag | none |
