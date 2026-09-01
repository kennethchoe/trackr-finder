# TrackR Finder

A small Android app that keeps a discontinued **TrackR Pixel** useful as a
Bluetooth-only locator — no cloud, no account, no subscription.

TrackR shut down in 2021 and bricked its own app. The hardware still works.

## The protocol

The Pixel is not a proprietary device. It implements the Bluetooth SIG
**Find Me Profile**, so ringing it is a single one-byte GATT write:

| Function | Service | Characteristic | Value |
|---|---|---|---|
| Ring | `0x1802` Immediate Alert | `0x2A06` Alert Level | `0x01` mild / `0x02` high |
| Silence | `0x1802` | `0x2A06` | `0x00` |
| Battery | `0x180F` Battery | `0x2A19` Battery Level | read, 0–100 |

Devices advertise with a name beginning `tkr`. There is **no pairing, bonding, or
authentication** — the Find Me profile is unauthenticated by design.

Protocol confirmed against Daniel Weidman's Web Bluetooth proof-of-concept:
<https://github.com/danielweidman/TrackR-Web-Bluetooth-API>

The ring/battery mechanism is therefore not TrackR-specific. Anything
implementing Immediate Alert can be rung — including many cheap "iTag" style
trackers, and a surprising number of headphones and wearables, since Find Me
ships in the Nordic and TI reference stacks.

Tile, AirTag and Samsung SmartTag cannot be rung: their alert commands are
authenticated against a key provisioned to the owner's account.

## Features

- Live scan with an RSSI proximity bar and a rough distance estimate
- **Ring it** / **Stop** — one-byte GATT writes
- Battery percentage, read opportunistically during the ring connection
- **Rename** — a local nickname per device, keyed by MAC. Useful because every
  Pixel advertises as plain `tkr`, so they are otherwise indistinguishable
- **Show all Bluetooth devices** — a diagnostic drawer listing every advertiser,
  with a "Try ringing" probe. Devices proven ringable are promoted to full cards
- **"Alert me if I leave this behind"** — a foreground service watches one
  tracker, notifies you when it drops out of range, and records the last GPS
  coordinate where it was heard

## What it cannot do

- **Direction.** RSSI gives hot/cold, not bearing. Range is ~10–30 m.
- **Find it once it is out of range.** TrackR's crowd-sourced network is gone
  and cannot be recreated. The last-seen coordinate is *your phone's* position
  when it last heard the tracker, not the tracker's own position — useful for
  something you walked away from, useless if the tracker is what moved.
- **React to the tracker's button.** Never publicly reverse-engineered.

## Three implementation notes

**Ring capability cannot be read from an advertisement.** The BLE advertising
packet is 31 bytes and most devices do not list their full service set in it —
the GATT service list is only visible after connecting. So rather than guessing,
ringing doubles as the probe: a successful ring or a definitive "no Immediate
Alert" is cached per MAC, and the UI promotes or demotes the device accordingly.


**Background scanning requires a `ScanFilter`.** Android returns nothing from
unfiltered BLE scans once the screen is off. Discovery therefore uses an
unfiltered scan (foreground only), while the alert service pins a filter to one
MAC address — which is why you must pick a device before it can watch it.

**No `ACCESS_BACKGROUND_LOCATION`.** A foreground service declaring the
`location` type may access location without that permission, provided the
service starts while the app is visible. The app is built that way deliberately.

## Install

No release APK is published yet. Build it yourself (below), or wait for the
F-Droid listing.

## Build

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21
export ANDROID_HOME=$HOME/Library/Android/sdk   # or your SDK location
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"

./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17+, Android SDK 36. Tested on a Galaxy S25 Ultra (Android 16).

## Not affiliated with TrackR

This is an independent, unofficial project. It is not affiliated with,
authorised by, or endorsed by TrackR Inc., Adero, or any successor. "TrackR" is
used only to identify the hardware this software interoperates with. No TrackR
branding, logos, or assets are included or reproduced here.

TrackR discontinued service in 2021; this project exists so the hardware people
already own does not become landfill.

## Licence

MIT — see [LICENSE](LICENSE).
