# Fork changes

This fork of [humblemuzzu/dumbtv](https://github.com/humblemuzzu/dumbtv) carries
three changes, all in `launcher/`. Verified on a **Fire TV Stick 4K Max
(`AFTKRT` / `karat`), Fire OS 8.1.8.5 (Android 11, SDK 30)**, 1920x1080 at
density 320.

| # | Change | Why |
|---|---|---|
| 1 | `SYSTEM_ALERT_WINDOW` in the manifest | makes the HOME takeover actually work on Fire OS 8 |
| 2 | New wrapping `Grid` layout for the apps block | tiles stop at the screen edge and wrap, instead of running off to the right |
| 3 | Rounded panel behind the grid | the tvOS "shelf" look |

---

## 1. HOME takeover on Fire OS 8 (Android 11)

Upstream `docs/GOTCHAS.md` lists this as an open question:

> **HOME does not move to dumbtv home yet.** Android 11 blocks the Guard's
> alarm-fired launch as a background activity start (logcat:
> `Background activity start ... callingUidHasAnyVisibleWindow: false`).

Android 11 exempts background activity starts for an app that holds *display
over other apps*. The launcher declared no permissions, so there was nothing to
exempt — and `appops set ... SYSTEM_ALERT_WINDOW allow` would not stick, because
an undeclared permission has no entry to flip:

```
SYSTEM_ALERT_WINDOW: default; rejectTime=+1m34s340ms ago
```

Declaring it in `launcher/AndroidManifest.xml` fixes that. After rebuild and
`appops set tv.dumbtv.home SYSTEM_ALERT_WINDOW allow`:

```
SYSTEM_ALERT_WINDOW: allow
Bound services:{Service[label=dumbtv home button, ...]}
(no background-activity-start denial in logcat)
```

A real remote HOME press then lands on `tv.dumbtv.home/.Home`, and it survives a
reboot.

**Cost:** the launcher is no longer zero-permission on Fire OS 8. It still has no
`INTERNET` permission, so it cannot phone home either way.

**Do not test this with `adb shell input keyevent KEYCODE_HOME`.** An injected
keyevent reaches the resolved HOME activity without producing the window-state
change the Guard watches, so it shows Amazon's launcher and reads as a failure.
Upstream's `verify.sh` says the same thing. Press HOME on the physical remote.
Likewise, `AmazonProfileService: shouldAbortActivityStart()` in logcat is a red
herring — read the next line, which says *"do not abort activity start"*.

## 2. Wrapping grid (`Grid.java`)

Upstream draws the apps row as a `HorizontalScrollView` + `LinearLayout`, which
scrolls sideways forever. `Grid` is a small `ViewGroup` that lays equal-size
tiles out in as many columns as the width holds and wraps to the next line:

- columns = `(inner + gap) / (tileWidth + gap)`, so it adapts to tile size
  (Options › Tile size) and to the screen
- leftover width widens the gaps up to double, then centres the remainder — a
  short last column reads as deliberate spacing rather than a dead right edge
- children size themselves, so `Tile` is unchanged

Vertical scrolling stays line-by-line: `Home.rowBlocks()` now contributes **one
block per grid line**, so a press down stops at the next line instead of paging,
and the first line's block starts at the `APPS` title so the top of the page is
reachable.

Focus navigation is Android's own geometric focus search, so left/right/up/down
inside the grid needs no code. Two call sites were adapted: `nearest()` (used
when moving down from the Options/Settings chips) now only considers tiles on the
grid's **first** line, and `shift()` / `stopMoving()` (tile reordering) cast the
parent to `ViewGroup` rather than `LinearLayout`.

On a 1920x1080 stick at density 320 with medium tiles this gives 4 columns.

## 3. Grid panel

The grid gets a rounded translucent fill (`Ui.PANEL`, white at 8%) with a 28dp
radius, inset 40dp from the screen edge, padded so tiles still line up with the
`APPS` title at 56dp.

## New options

Both appear in Options, right after Accent color:

| Option | Key in `home.conf` | Values | Default |
|---|---|---|---|
| App layout | `layout` | `grid`, `row` | `grid` |
| Grid panel | `panel` | `on`, `off` | `on` |

`row` restores upstream's sideways-scrolling strip exactly. Switching rebuilds
the view tree in place, so the change shows as soon as you leave Options. The
panel row dims when the layout is `row`, since it does not apply there.

The Inputs row is deliberately left as a horizontal strip — there are rarely
enough inputs to wrap.

---

## Build and install

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home
export ANDROID_HOME=$HOME/Library/Android/sdk
bash launcher/build.sh                      # -> launcher/build/dumbtv-home.apk
adb -s <device> install -r launcher/build/dumbtv-home.apk
```

Then apply the HOME hooks (what `launcher/install.sh` does, minus its install
check, which false-fails on incremental-install output):

```bash
adb -s <device> shell appops set tv.dumbtv.home SYSTEM_ALERT_WINDOW allow
adb -s <device> shell appops set tv.dumbtv.home GET_USAGE_STATS allow
adb -s <device> shell settings put secure enabled_accessibility_services \
    tv.dumbtv.home/tv.dumbtv.home.Guard
adb -s <device> shell settings put secure accessibility_enabled 1
adb -s <device> shell cmd package set-home-activity tv.dumbtv.home/.Home
```

Reverse the whole thing with `bash launcher/install.sh --undo`.

### Build gotchas

- **`build.sh` needs a real `platforms/android-28`.** It compiles with
  `-Xlint:all -Werror`, so a newer `android.jar` (e.g. android-37) fails on APIs
  that were not deprecated in API 28. Aliasing the directory does not work. Get
  the real one:
  ```bash
  curl -O https://dl.google.com/android/repository/platform-28_r06.zip
  unzip platform-28_r06.zip         # the directory inside is named android-9
  mv android-9 $ANDROID_HOME/platforms/android-28
  ```
  Do not raise `MIN_SDK` above 30 — it drives `--min-sdk-version`, and the APK
  would stop installing on Android 11.
- **Keep `launcher/release.keystore`.** It is gitignored and generated on first
  build. Android refuses to update an APK signed with a different key, so losing
  it means uninstall-then-install — which **deletes `home.conf`**, so hidden
  tiles, renames and order go back to default.
- **`adb` cannot read or write `/sdcard/Android/data` on Fire OS 8**, so
  `home.conf` cannot be pushed or pulled. Configure on screen via Options.
