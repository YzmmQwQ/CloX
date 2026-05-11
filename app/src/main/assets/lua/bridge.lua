-- Bridge — Lua side of the JNI ↔ Lua event bus
-- Android calls Bridge.dispatchEvent(event, payload) from JNI

Bridge = {}
Bridge._handlers = {}

-- Register a handler for a named event
function Bridge.onEvent(event, handler)
    if not Bridge._handlers[event] then
        Bridge._handlers[event] = {}
    end
    table.insert(Bridge._handlers[event], handler)
end

-- Called from native (CocosManager.kt → JNI → Lua) to dispatch events
function Bridge.dispatchEvent(event, payload)
    local handlers = Bridge._handlers[event]
    if handlers then
        for _, h in ipairs(handlers) do
            local ok, err = pcall(h, payload)
            if not ok then
                print("[Bridge] handler error for event=" .. event .. ": " .. tostring(err))
            end
        end
    end
end

-- Sends a Lua event up to Kotlin via JNI (reverse bridge)
-- Kotlin side: LuaBridge.onLuaEvent() is called by native code
function Bridge.sendToAndroid(event, payload)
    -- native function registered by Axmol Lua runtime:
    -- luaoc.callStaticMethod("com/ailife/clox/cocos/LuaBridge", "onLuaEvent", {event, payload})
    if luaoc then
        luaoc.callStaticMethod(
            "com/ailife/clox/cocos/LuaBridge",
            "onLuaEvent",
            { event, payload or "" }
        )
    end
end

return Bridge
