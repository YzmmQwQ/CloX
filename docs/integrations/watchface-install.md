# Install a Watchface and Set It Active (Intent)

Hand CloX a `.watch` file; it copies the face into the watchface directory **and** makes it the
active watchface — in a single step.

← Back to the [Integration Guide](../../INTEGRATION.md)

---

## Overview

CloX exports `WatchfaceImportHandlerActivity` with an `ACTION_VIEW` intent filter for `.watch`
files. Launch it with a `content://` (or `file://`) URI pointing at a `.watch` file and CloX will:

1. Copy the file into its watchface directory.
2. Set it as the active watchface.
3. Open the watchface selector on the newly installed face.

No permission is required. This is the right API when you *have a face file* and want it shown
immediately. To switch to a face that is already installed, use
[Change watchface](watchface-change.md) instead.

---

## Firing the intent

**Action:** `android.intent.action.VIEW`
**Data:** a `content://` or `file://` URI whose file name ends in `.watch`
**Type:** `*/*` (the filter is MIME-agnostic; matching is by the `.watch` extension)

Because the URI usually comes from **your** app's storage, expose it with a `FileProvider` and grant
read access to CloX with `FLAG_GRANT_READ_URI_PERMISSION`.

**Kotlin example (sharing a file from your app via FileProvider):**

```kotlin
// watchFile is a .watch file in your app's storage
val uri = FileProvider.getUriForFile(context, "$packageName.fileprovider", watchFile)

val intent = Intent(Intent.ACTION_VIEW).apply {
    setDataAndType(uri, "*/*")
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    // Target CloX's handler directly to skip the app chooser:
    setClassName("com.ailife.clox",
        "com.ailife.clox.watchface.WatchfaceImportHandlerActivity")
}
context.startActivity(intent)
```

Omit the `setClassName(...)` line to let the system show a chooser (useful if more than one watch
app can handle `.watch` files).

**ADB (testing with a pushed file):**

```bash
adb push MyCoolFace.watch /sdcard/MyCoolFace.watch
adb shell 'am start -a android.intent.action.VIEW \
  -d file:///sdcard/MyCoolFace.watch -t "*/*" \
  -n com.ailife.clox/.watchface.WatchfaceImportHandlerActivity'
```

---

## Requirements & behaviour

- The file name **must end in `.watch`** (case-insensitive). Otherwise CloX shows an
  "invalid file" toast and does nothing.
- On success CloX shows an "imported" toast, sets the face active, and navigates to the watchface
  selector.
- If a face with the same file name already exists it is imported alongside — this API does not
  overwrite in place. To overwrite or manage existing files, use the
  [Watchface directory API](watchface-directory.md).
- The activity finishes immediately after handling; there is no `startActivityForResult` result.

---

## See also

- [Change watchface](watchface-change.md) — activate an already-installed face by name.
- [Watchface directory](watchface-directory.md) — programmatic install/overwrite/delete without UI.
