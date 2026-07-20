# CloX External Data API

← Back to the [Integration Guide](INTEGRATION.md)

Allows any Android app to push arbitrary string data into CloX's watchface bridge via a broadcast intent. Data is stored in 10 persistent slots and immediately available to the active watchface.

---

## Overview

CloX exposes a single broadcast action. When received, the payload is:

1. Validated (slot range, required extras present)
2. Written to SharedPreferences (persists across reboots)
3. Pushed to the active Lua watchface immediately via `WF.thirdParty.slots[n]`

No API key or permission is required. Any installed app can write to the slots.

---

## Writing data

**Broadcast action:** `com.ailife.clox.WRITE_DATA`

**Required extras:**

| Extra | Type   | Description                              |
|-------|--------|------------------------------------------|
| `slot` | `int`  | Target slot number. **Must be 1–10.**    |
| `data` | `String` | The string payload to store.           |

**Optional extras:**

| Extra | Type   | Description                                            |
|-------|--------|--------------------------------------------------------|
| `key` | `String` | A label or identifier for this slot (e.g. `"temperature"`). Defaults to `""`. |

**Kotlin example:**

```kotlin
val intent = Intent("com.ailife.clox.WRITE_DATA").apply {
    setPackage("com.ailife.clox")                 // target CloX specifically
    putExtra("slot", 1)
    putExtra("data", "24.5")
    putExtra("key",  "outdoor_temp")
}
context.sendBroadcast(intent)
```

**Java example:**

```java
Intent intent = new Intent("com.ailife.clox.WRITE_DATA");
intent.setPackage("com.ailife.clox");
intent.putExtra("slot", 1);
intent.putExtra("data", "24.5");
intent.putExtra("key",  "outdoor_temp");
context.sendBroadcast(intent);
```

**ADB shell example (testing from command line):**

```bash
adb shell 'am broadcast -a com.ailife.clox.WRITE_DATA \
  -n com.ailife.clox/.cocos.bridge.ExternalDataReceiver \
  --ei slot 1 \
  --es data "hello world" \
  --es key "test"'
```

> **Note:** `setPackage("com.ailife.clox")` is recommended so the broadcast is delivered only to CloX, not to any other receiver that might register for the same action.

---

## Slots

There are **10 independent slots** numbered **1 to 10**. Each slot stores:

| Field       | Type   | Description                          |
|-------------|--------|--------------------------------------|
| `data`      | String | The last value written               |
| `key`       | String | The label supplied with the write    |
| `timestamp` | Long   | Unix epoch milliseconds of the write |

- Slots are **independent** — writing slot 3 does not affect slot 1.
- Slot data **persists across reboots**.
- Each write **overwrites** the previous value in that slot.
- Writing an invalid slot number (< 1 or > 10) or omitting `data` is **silently ignored**.

---

## Reading from a watchface (Lua)

Data is available in the global `WF.thirdParty.slots` table immediately after a write is received. Slots are **1-indexed** in Lua.

```lua
-- Read slot 1
local slot1 = WF.thirdParty.slots[1]
print(slot1.data)       -- "24.5"
print(slot1.key)        -- "outdoor_temp"
print(slot1.timestamp)  -- 1716700800000 (unix ms)

-- React to updates
WatchfaceBridge.onChange("thirdParty", function(data)
    local temp = data.slots[1].data
    updateTemperatureDisplay(temp)
end)
```

### Default state

All 10 slots start with empty strings and timestamp 0. A slot that has never been written will have:

```lua
{ data = "", key = "", timestamp = 0 }
```

---

## Behaviour on write

1. CloX receives the broadcast (works even when the app is in the background).
2. The slot is written to SharedPreferences synchronously.
3. `WF.thirdParty` is pushed to the active watchface on the GL thread within milliseconds.
4. If no watchface is currently active the data is still persisted and will be available when a watchface next loads.

---

## Use cases

| Slot | Key           | Data example      | Description                        |
|------|---------------|-------------------|------------------------------------|
| 1    | `temp`        | `"24.5"`          | Outdoor temperature from IoT sensor|
| 2    | `steps_goal`  | `"8000"`          | Daily step goal from fitness app   |
| 3    | `next_bus`    | `"7 min"`         | Next bus arrival from transit app  |
| 4    | `hr_alert`    | `"1"`             | Heart rate alert flag              |
| 5    | `message`     | `"Meeting at 3pm"`| Custom message from companion app  |

---

## Limitations

- Slot data is always a **plain string**. Encode structured data as JSON if needed.
- Maximum string length is not enforced by CloX but is bounded by Android's IPC limits (~1 MB per intent extra in practice; keep payloads short).
- Writes from multiple apps to the same slot are **last-write-wins**.
- No read-back mechanism — third-party apps cannot query the current slot value via this API.
