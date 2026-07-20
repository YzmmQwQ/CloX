# Change the Active Watchface (Broadcast)

Switch CloX to a watchface **that is already installed**, by name, via a broadcast intent.

← Back to the [Integration Guide](../../INTEGRATION.md)

---

## Overview

CloX registers a broadcast receiver (`ChangeFaceReceiver`) for the action
`com.android.watchengine.changeface`. When it receives one, it looks up an installed face whose name
matches the `faceName` extra and makes it the active watchface immediately.

- No permission and no API key required — any installed app can send it.
- Works while CloX is in the background.
- Only affects **already-installed** faces. To add a new face, see
  [Install a watchface](watchface-install.md).

---

## Sending the broadcast

**Action:** `com.android.watchengine.changeface`

**Required extra:**

| Extra | Type | Description |
|-------|------|-------------|
| `faceName` | `String` | Name of the target face. See [name matching](#name-matching) below. |

**Kotlin example:**

```kotlin
val intent = Intent("com.android.watchengine.changeface").apply {
    setPackage("com.ailife.clox")   // deliver only to CloX
    putExtra("faceName", "MyCoolFace")
}
context.sendBroadcast(intent)
```

**Java example:**

```java
Intent intent = new Intent("com.android.watchengine.changeface");
intent.setPackage("com.ailife.clox");
intent.putExtra("faceName", "MyCoolFace");
context.sendBroadcast(intent);
```

**ADB (testing):**

```bash
adb shell 'am broadcast -a com.android.watchengine.changeface \
  -n com.ailife.clox/.watchface.ChangeFaceReceiver \
  --es faceName "MyCoolFace"'
```

---

## Name matching

`faceName` is forgiving — CloX only compares the **base name**:

1. Backslashes are treated as `/`, and only the **last path segment** is used
   (`skins/sport/MyCoolFace.watch` → `MyCoolFace.watch`).
2. The `.watch` extension is stripped (`MyCoolFace.watch` → `MyCoolFace`).
3. Comparison is **case-insensitive** (`mycoolface` matches `MyCoolFace`).

So all of these select the same face:

- `MyCoolFace`
- `MyCoolFace.watch`
- `mycoolface`
- `/any/base/dir/MyCoolFace.watch`

This means the sender does not need to know CloX's storage path or which sub-folder the face lives
in — just its file name.

---

## Behaviour

1. CloX scans the watchface directory and matches the first face by base name.
2. If a match is found, it is set as the active watchface (persisted across reboots).
3. If **no** face matches, the broadcast is **silently ignored** (a warning is logged to logcat).
4. A missing or empty `faceName` is ignored.

There is no result or acknowledgement returned to the sender. To confirm a face is present before
switching, list the directory with the [Watchface directory API](watchface-directory.md).

---

## See also

- [Install a watchface](watchface-install.md) — add a new `.watch` file and set it active in one step.
- [Watchface directory](watchface-directory.md) — enumerate installed faces to discover valid names.
