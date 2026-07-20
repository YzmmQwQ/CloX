# CloX Integration Guide for Developers

CloX (`com.ailife.clox`) is a smartwatch launcher and watchface engine. It exposes a small set of
public APIs so other apps — companion apps, transfer tools, IoT bridges, watchface stores — can
push data, change the active watchface, install new faces, and manage the watchface directory.

This page is the **root index**. Each API has its own reference page linked below.

---

## APIs at a glance

| I want to… | Mechanism | Component | Permission | Reference |
|------------|-----------|-----------|------------|-----------|
| Change the **active** watchface to one already installed | Broadcast | `ChangeFaceReceiver` | none | [Change watchface](integrations/watchface-change.md) |
| **Install** a `.watch` file and make it active in one step | Intent (`ACTION_VIEW`) | `WatchfaceImportHandlerActivity` | none | [Install watchface](integrations/watchface-install.md) |
| **List / read / write / delete** faces in the watchface folder | ContentProvider | `WatchfaceProvider` | `ACCESS_WATCHFACES` (grant once) | [Watchface directory](integrations/watchface-directory.md) |
| Push arbitrary key/value **data** to the active watchface | Broadcast | `ExternalDataReceiver` | none | [External data API](EXTERNAL_DATA_API.md) |

All of the above work on **Android 10+** and while CloX is in the background.

---

## Identifiers reference

| Purpose | Value |
|---------|-------|
| App package | `com.ailife.clox` |
| Change-face broadcast action | `com.android.watchengine.changeface` |
| External-data broadcast action | `com.ailife.clox.WRITE_DATA` |
| Watchface directory provider authority | `com.ailife.clox.watchfaces` |
| Watchface directory permission | `com.ailife.clox.permission.ACCESS_WATCHFACES` |
| File-import handler | `com.ailife.clox/.watchface.WatchfaceImportHandlerActivity` |

---

## Permission model

Most entry points are **open** — any installed app can broadcast to them or fire the install intent,
so no permission is needed to change or install a watchface.

The one guarded surface is the **watchface directory API** (`WatchfaceProvider`), because it grants
raw read/write over the face files. It is protected by a single custom permission:

```xml
<uses-permission android:name="com.ailife.clox.permission.ACCESS_WATCHFACES" />
```

`ACCESS_WATCHFACES` is `protectionLevel="dangerous"`: the requesting app calls
`requestPermissions()` **once**, Android shows the system consent prompt, and the grant is managed
(and revocable) in system Settings. CloX shows no dialog of its own. See the
[Watchface directory](integrations/watchface-directory.md) page for details.

> The permission controls **only** the watchface directory. It gives no access to any other CloX
> data or storage.

---

## Choosing between the watchface APIs

The three watchface APIs overlap slightly — pick by what you have and what you want to happen:

- **You know the name of a face already on the watch** → [broadcast a change](integrations/watchface-change.md). No files, no permission.
- **You have a `.watch` file to add and want it shown immediately** → [fire the install intent](integrations/watchface-install.md). Installs *and* sets active. No permission.
- **You need to browse, bulk-sync, overwrite, or delete faces programmatically** → [use the directory provider](integrations/watchface-directory.md). Requires the `ACCESS_WATCHFACES` grant.

A common pattern: push a face with the directory provider (or install intent), then broadcast a
change to activate a specific one by name.

---

## Targeting CloX explicitly

For broadcasts, always set the target package so the intent is delivered only to CloX and not to any
other app registered for the same action:

```kotlin
intent.setPackage("com.ailife.clox")
```

For the install intent, target the handler component directly if you want to skip the app chooser
(see that page).
