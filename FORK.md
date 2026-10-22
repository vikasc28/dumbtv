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

**No Amazon flash on HOME.** Upstream covers Amazon's home with a plain overlay,
but only once Amazon's window-state event arrives, which is after it has drawn,
so a frame or more of Amazon showed every time. The Guard now raises the cover
early where HOME can be seen coming: `onUserLeaveHint` on our Home (when no
start of ours caused it) and on `Watch`, and on screen-off over our Home, since a
remote or HDMI-CEC wake can land on Amazon's home. The cover shows the current
wallpaper and dim, handed over by Home, so the switch looks like the wallpaper
drawing first and the tiles following. Measured on the stick: HOME -> leave hint
-> cover drawn in 20 ms, Amazon resumes 30 ms later under it, our home 0.4 s
after, no Amazon frame in a 20 fps screenrecord. An app other than ours in front
still gets the late cover; nothing can see that HOME before Amazon draws.

The TV also needs `min_futurity=0` in `alarm_manager_constants` (`install.sh`
sets it); without it Amazon's home stayed up ~3 s on the TV.

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

The grid gets a rounded frosted pane (`Ui.glass`) with a 28dp radius, inset 40dp
from the screen edge, padded so tiles still line up with the `APPS` title at
56dp. It is a white wash fading top-to-bottom (15% to 5%) inside a 1dp hairline
edge at 20%, rather than a flat fill.

Android 11 has no backdrop blur — `RenderEffect` and
`Window.setBackgroundBlurRadius` are both API 31 — so the glass comes from
translucency and the lit edge, not from blurring what sits behind. It therefore
only reads as glass over a wallpaper (see fix 5); on a flat black background it
is just a slightly lighter rectangle.

## 4. Grid focus navigates in reading order

Right from the last tile on a line jumped up to the Options/Settings chips.
Android's focus search is geometric: it looks for a view lying to the right of
the focused one, finds nothing, and the event escapes upward.

`Grid.focusSearch` now walks its own children by index instead:

- right/left move to the next/previous tile, so the end of a line continues on
  the next one; at the very first and last tile the press is swallowed rather
  than escaping sideways
- down from a line whose column is empty on the next line still lands on that
  line's last tile, instead of skipping the grid entirely
- up from the first line and down from the last fall through to
  `super.focusSearch`, so the chips above and the Inputs row below stay
  reachable

Note that `ScrollView` handles up/down in `executeKeyEvent` before the focus
chain is consulted, which is why vertical cases also need `Home`'s own
`column.focusSearch` hook rather than `Grid` alone.

Do not test this with `adb shell input keyevent`: injected arrow keys reach the
focused view, but they are easy to misread — a key you press on the real remote
in the same window looks identical in a screenshot.

## 5. Wallpapers readable on Fire OS 8

Upstream reads wallpapers from the app's own external folder and documents
pushing them there:

```
adb push *.jpg /sdcard/Android/data/tv.dumbtv.home/files/wallpapers/
```

On Fire OS 8 that path is unreachable — `adb` gets `Permission denied` on
`/sdcard/Android/data` for read as well as write, so a host cannot deliver a
wallpaper at all, and the `Background -> Image file` option has nothing to show.

`Config.wallpapers()` now scans a second folder as well:

```
/sdcard/Pictures/dumbtv/        <- readable by the app, writable over adb
```

Both folders are read, the app's own one first, and the combined list is sorted
by name as before. This costs one permission:

```xml
<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
```

It is a runtime permission, so grant it after installing:

```
adb shell pm grant tv.dumbtv.home android.permission.READ_EXTERNAL_STORAGE
adb shell mkdir -p /sdcard/Pictures/dumbtv
adb push wallpaper.jpg /sdcard/Pictures/dumbtv/
```

The launcher still has no `INTERNET` permission.

### Generating a plain frost wallpaper

The pane only reads as glass over something, but it does not need a photo — a
soft radial gradient is enough, and it keeps tile artwork the brightest thing on
screen. One command, no image editor:

```
ffmpeg -f lavfi -i "gradients=s=1920x1080:c0=0x8a8b8f:c1=0x55565b:type=radial:x0=960:y0=380:nb_colors=2:d=1" \
       -vf "gblur=sigma=50,noise=alls=3:allf=t+u" -frames:v 1 frost-grey.png
```

`c0` is the lit centre and `c1` the corners; put `y0` above centre so the light
sits behind the panel's top edge. The `gblur` kills gradient banding on a TV
panel and the light `noise` stops the remaining flat areas from posterising.
Keep the two colours close together — a neutral grey a few steps apart reads as
a translucent pane, where a wide or saturated spread reads as a coloured
backdrop competing with the tile artwork.

Set **Image dim to 0%** for this. The default 40% black overlay is tuned for a
bright photo and crushes a dark gradient back to flat black, which looks
identical to the `black` background.

#### A light rounded slab

For the Apple-keynote shape — a cream rounded panel standing in a bright room —
`geq` gives the rounded-rectangle alpha mask in one expression, so this still
needs no image editor:

```
ffmpeg \
 -f lavfi -i "gradients=s=1920x1080:c0=0xd4cfc7:c1=0x9b968e:type=radial:x0=960:y0=620:nb_colors=2:d=1" \
 -f lavfi -i "gradients=s=1920x1080:c0=0xfcf9f4:c1=0xe2ded6:type=radial:x0=900:y0=300:nb_colors=2:d=1" \
 -filter_complex "[0:v]gblur=sigma=60[base]; \
 [1:v]format=rgba,geq=r='r(X,Y)':g='g(X,Y)':b='b(X,Y)':a='255*lte(hypot(max(abs(X-960)-726,0),max(abs(Y-540)-420,0)),64)'[slab]; \
 [base][slab]overlay=0:0,gblur=sigma=1.2,noise=alls=2:allf=t+u" \
 -frames:v 1 frost-slab.png
```

The alpha expression is a rounded-rect distance test: `726`/`420` are the half
width and half height minus the corner radius, and `64` is that radius. The
trailing `gblur=sigma=1.2` antialiases the corners, which `geq` produces hard.

Turn **Grid panel off** with this one. The wallpaper's slab already is the
panel, and leaving both on draws two offset rounded rectangles.

Note the launcher's text is white throughout (`Ui.TEXT`, plus the date at 60%
and the row headers at 40%), so a wallpaper this light washes it out. Either
raise Image dim or darken those constants.

## 9. Custom banners

A PNG at `/sdcard/Pictures/dumbtv/banners/<package>.png` replaces that app's
banner (`Catalog.art`); without one the APK's own banner or icon is used, as
upstream. It is read from the same public folder as the wallpapers, so no new
permission. 16:9, 640x360 for a 1080p screen. Example: TizenTube shown as YouTube,
the SVG logo scaled to the tile's full height with the sides filled in its own red
so the tile is all logo:

```
magick -background none -density 2400 youtubeIcon.svg -resize x360 logo.png
magick -size 640x360 'xc:#FF0000' logo.png -gravity center -composite -depth 8 PNG32:banner.png
adb push banner.png /sdcard/Pictures/dumbtv/banners/io.gh.reisxd.tizentube.cobalt.png
printf 'name.io.gh.reisxd.tizentube.cobalt=YouTube\n' > home.conf   # rename via section 6
adb push home.conf /sdcard/Pictures/dumbtv/
```

A banner is loaded once per launch; restart the launcher after pushing a new one.

## New options

Both appear in Options, right after Accent color:

| Option | Key in `home.conf` | Values | Default |
|---|---|---|---|
| App layout | `layout` | `dock`, `grid`, `row` | `dock` |
| Grid panel | `panel` | `on`, `off` | `on` |

`row` restores upstream's sideways-scrolling strip exactly. Switching rebuilds
the view tree in place, so the change shows as soon as you leave Options. The
panel row dims when the layout is `row`, since it does not apply there.

The Inputs row is deliberately left as a horizontal strip — there are rarely
enough inputs to wrap.

## 7. Dock layout (`Glow.java`)

The Apple TV home, after [cleanyourtv](https://github.com/Gathin23/cleanyourtv)'s
launcher, which cannot take HOME on Fire OS itself (its README: "the debloat half
only"). Here it runs on top of fix 1.

- All apps sit in the one frosted grid panel, pushed to the bottom of the screen
  by a flexible spacer and without the `APPS` title, so nothing scrolls until the
  apps are taller than the screen. Its first line plays the dock. A separate dock
  band was tried first and dropped: its tiles were spaced differently from the
  grid's, Right did not wrap into the grid, and it needed a second panel.
- Focusing any app shows its name large above the panel and tints a radial glow
  behind it. The colour is the banner's average, raised to at least 75%
  brightness so a dark banner still glows; it is computed once per app from a
  16x9 draw of a copy of the banner and cached.
- Navigation and Move are the grid's (fix 4): Right from the end of a line wraps.
- At 1080p with medium tiles two lines fit; a third scrolls.

## 8. Inputs dropdown

The Inputs row is gone in every layout. An `Inputs` chip left of Options opens a
`ListPopupWindow` anchored under it, with a dot after each connected input. Left
from that chip stays put instead of dropping into the dock. The `Inputs` option
(`inputs=off`) hides the chip, for a stick with nothing plugged into HDMI.

Picking an input opens `Watch.java`, which plays it full screen in a `TvView`.
Upstream fired `ACTION_VIEW` on the passthrough channel URI, which nothing on Fire
OS answers ("Could not open that"); Amazon's own switcher answers
`tvinput://tunenow?inputId=...` but requires `com.amazon.tv.permission.TUNE_INPUT`,
which is `signature|amazon`. Watch starts without NEW_TASK/RESET_TASK flags: with
them it closed straight away on the TV (most likely because it joined the
singleTask launcher's task). Back returns home. HDMI-CEC device entries (an input with a parent, such as "Fire TV
Stick" under HDMI 1) are left out, so each port is listed once.

Not ported: the floating spring-animated glass highlight, the cinematic
backdrop, themes and module cards. The dock is fixed at four tiles.

## Reset settings

A second reset row sits under upstream's "Reset order, names and hidden tiles":

| Row | Clears |
|---|---|
| Reset order, names and hidden tiles | `name.`, `apps.`, `inputs.` keys (upstream) |
| **Reset settings** | every `Config.CHOICES` key, so each falls back to its default |

The two are deliberately separate: one is about *which* tiles you see and what
they are called, the other about how the screen looks. Neither touches the
other, so re-theming never costs you a curated app list.

Defaults are repinned to this fork's look, which is what makes the row useful —
reset lands on the configuration below rather than on upstream's black
background:

| Key | Upstream default | Here |
|---|---|---|
| `accent` | `blue` | `white` |
| `background` | `black` | `image` |
| `dim` | `40` | `0` |
| `rotate` | `off` | `daytime` |

`background=image` with no wallpaper present degrades to opaque black (the
`BACKGROUNDS` entry for `image`), so the new default is safe on a device that
has never had a wallpaper pushed.

Wallpapers must be **PNG, or a baseline JPEG**. A 735x490 *progressive* JPEG
pushed to the wallpaper folder was listed and selected correctly but drew
nothing — the screen stayed at the background color — while a PNG in the same
folder rendered immediately. `BitmapFactory` is the likely culprit rather than
anything in this fork, so if a wallpaper refuses to appear, re-save it as PNG
before debugging the launcher.


---

## 6. Changing settings from a host on Fire OS 8

`home.conf` lives next to the app's own files, under
`/sdcard/Android/data/tv.dumbtv.home/files/`. Fire OS 8 denies adb that path for
reading as well as writing, so upstream's "pull it, edit it, push it back" route
does not exist on this platform and every setting has to be changed with the
remote. That is fine for one toggle and painful for a batch.

`Config` now also looks for a `home.conf` in the public wallpaper folder, folds
whatever keys it finds into the real one on load, and then consumes it:

```
printf 'background=image\ndim=0\nlayout=grid\npanel=on\n' > home.conf
adb push home.conf /sdcard/Pictures/dumbtv/
adb shell am force-stop tv.dumbtv.home        # next launch picks it up
```

Only the keys present in the drop change; everything else is left alone. It is
consumed rather than layered so it cannot shadow the Options UI — a key left
sitting in a permanent override file would silently undo every on-screen change
to it.

One wrinkle: under scoped storage the app often cannot delete a file the host
wrote, and the delete failure is logged rather than fatal. The drop is then
re-applied on each launch, which is harmless but does mask Options for those
keys, so **delete it from the host once it has been picked up**:

```
adb shell rm -f /sdcard/Pictures/dumbtv/home.conf
```

Note that `wallpapers()` filters on image extensions, so a `home.conf` sharing
the wallpaper folder is never mistaken for a wallpaper.

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
  `home.conf` cannot be pushed or pulled directly. Configure on screen via
  Options, or drop a partial `home.conf` in `Pictures/dumbtv` (section 6).
