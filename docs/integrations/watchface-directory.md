# Watchface Directory API (ContentProvider)

Programmatic read/write access to CloX's watchface directory: list, read, add, overwrite, and delete
face files, and create folders. This is the **management** API and is the only CloX surface guarded
by a permission.

← Back to the [Integration Guide](../../INTEGRATION.md)

---

## Why a provider (and not a file path)

Watchfaces live on CloX's app-private external storage so the Lua renderer can read them by absolute
path. On Android 10+ no other app can reach that directory by file path. `WatchfaceProvider` bridges
the gap: other apps work over `content://` URIs and CloX performs the file I/O on their behalf. Files
still land on the Lua-readable path — no copies, no relocation.

---

## Permission

Access is gated by a single custom permission:

```xml
<uses-permission android:name="com.ailife.clox.permission.ACCESS_WATCHFACES" />
```

It is `protectionLevel="dangerous"` — request it **once** at runtime; Android shows the system
consent prompt and manages the grant in Settings:

```kotlin
val PERM = "com.ailife.clox.permission.ACCESS_WATCHFACES"
if (checkSelfPermission(PERM) != PackageManager.PERMISSION_GRANTED) {
    requestPermissions(arrayOf(PERM), REQ_CODE)
}
```

The permission controls **only** the watchface directory. Every request is resolved against the
canonical watchface folder and rejected if it escapes it, so the provider can never touch other CloX
storage.

---

## Authority and URIs

**Authority:** `com.ailife.clox.watchfaces`

| URI | Refers to |
|-----|-----------|
| `content://com.ailife.clox.watchfaces` | the whole watchface directory (query lists it) |
| `content://com.ailife.clox.watchfaces/MyFace.watch` | a top-level face |
| `content://com.ailife.clox.watchfaces/Sport/MyFace.watch` | a face inside the `Sport` folder |
| `content://com.ailife.clox.watchfaces/Sport` | a folder |

```kotlin
val base = Uri.parse("content://com.ailife.clox.watchfaces")
```

---

## List faces — `query()`

Query the base URI to enumerate the directory: top-level faces, sub-folders, and the faces inside
those folders.

```kotlin
resolver.query(base, null, null, null, null)?.use { c ->
    val nameCol = c.getColumnIndex("_display_name")
    val relCol  = c.getColumnIndex("relative_path")
    val sizeCol = c.getColumnIndex("_size")
    val dirCol  = c.getColumnIndex("is_dir")
    while (c.moveToNext()) {
        val name = c.getString(nameCol)
        val rel  = c.getString(relCol)     // e.g. "Sport/MyFace.watch"
        val size = c.getLong(sizeCol)
        val isDir = c.getInt(dirCol) == 1
    }
}
```

**Columns returned:**

| Column | Type | Description |
|--------|------|-------------|
| `_display_name` | String | File or folder name |
| `relative_path` | String | Path relative to the watchface root (use this to build item URIs) |
| `_size` | Long | Bytes (0 for folders) |
| `last_modified` | Long | Unix epoch millis |
| `is_dir` | Int | `1` for a folder, `0` for a file |
| `mime_type` | String | `application/octet-stream` for files, `vnd.android.document/directory` for folders |

Querying a specific file/folder URI returns a single metadata row (empty cursor if it doesn't exist).

---

## Read a face — `openInputStream()`

```kotlin
val uri = Uri.withAppendedPath(base, "MyFace.watch")
resolver.openInputStream(uri)?.use { input ->
    // read the .watch (ZIP) bytes
}
```

---

## Add or overwrite a face — `openOutputStream()`

Write mode creates the file (and any parent folder) if needed, so this both **adds** new faces and
**overwrites** existing ones. Bytes land directly in the Lua-readable directory.

```kotlin
val uri = Uri.withAppendedPath(base, "Sport/NewFace.watch")
resolver.openOutputStream(uri, "w")?.use { output ->
    myWatchBytes.inputStream().copyTo(output)
}
```

> Writing a face does **not** make it active. To activate it afterwards, send a
> [change-face broadcast](watchface-change.md) with the face name.

---

## Delete a face or folder — `delete()`

```kotlin
resolver.delete(Uri.withAppendedPath(base, "MyFace.watch"), null, null)   // a face
resolver.delete(Uri.withAppendedPath(base, "Sport"), null, null)          // a folder + contents
```

Returns `1` if something was deleted, `0` otherwise.

---

## Create a folder — `insert()`

```kotlin
resolver.insert(Uri.withAppendedPath(base, "Sport"), null)   // returns the folder URI, or null
```

New **files** are created through `openOutputStream(..., "w")`, not `insert()`.

---

## Notes

- Only `.watch` files are surfaced by `query()`; other file types in the folder are ignored.
- Sub-folders are supported one level deep, matching what the CloX selector displays.
- The provider is exported and reachable from any app **holding the permission** — there is no
  per-caller allow-list.

---

## See also

- [Change watchface](watchface-change.md) — activate a face after writing it.
- [Install a watchface](watchface-install.md) — one-shot add-and-activate from a file URI (no permission).
