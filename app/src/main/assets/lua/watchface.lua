-- CloX Watchface Scene
-- Rendered on the Axmol GL thread. All home screen content lives here.

require "bridge"

local WatchfaceScene = class("WatchfaceScene", cc.Scene)

function WatchfaceScene:ctor()
    cc.Scene.ctor(self)
end

function WatchfaceScene:onEnter()
    local director = cc.Director:getInstance()
    local size     = director:getWinSize()
    local cx, cy   = size.width / 2, size.height / 2

    -- ── Background ──────────────────────────────────────────────────────────
    local bg = cc.LayerColor:create(cc.c4b(0, 0, 0, 255))
    self:addChild(bg, -1)

    -- ── Digital clock label ─────────────────────────────────────────────────
    local clockLabel = cc.Label:createWithSystemFont("00:00:00", "sans-serif", 52)
    clockLabel:setPosition(cx, cy + 20)
    clockLabel:setTextColor(cc.c4b(255, 255, 255, 255))
    self:addChild(clockLabel, 2)

    -- ── Date label ──────────────────────────────────────────────────────────
    local dateLabel = cc.Label:createWithSystemFont("", "sans-serif", 18)
    dateLabel:setPosition(cx, cy - 20)
    dateLabel:setTextColor(cc.c4b(180, 180, 180, 255))
    self:addChild(dateLabel, 2)

    -- ── Rotating accent square (animation proof) ────────────────────────────
    local square = cc.DrawNode:create()
    square:drawSolidRect(cc.p(-16, -16), cc.p(16, 16), cc.c4f(0.12, 0.53, 0.9, 0.85))
    square:setPosition(cx, cy - 80)
    self:addChild(square, 2)
    square:runAction(cc.RepeatForever:create(cc.RotateBy:create(3.0, 360)))

    -- ── Touch test button (Axmol UI widget) ─────────────────────────────────
    local touchFeedback = cc.Label:createWithSystemFont("", "sans-serif", 20)
    touchFeedback:setPosition(cx, cy - 150)
    touchFeedback:setTextColor(cc.c4b(80, 220, 80, 255))
    self:addChild(touchFeedback, 3)

    local btn = ccui.Button:create()
    btn:setTitleText("Touch Test")
    btn:setTitleFontSize(18)
    btn:setTitleColor(cc.c3b(255, 255, 255))
    btn:setPosition(cx, cy - 200)
    btn:addTouchEventListener(function(sender, eventType)
        if eventType == ccui.TouchEventType.ended then
            touchFeedback:setString("Touch works!")
            touchFeedback:stopAllActions()
            touchFeedback:runAction(cc.Sequence:create(
                cc.DelayTime:create(1.5),
                cc.FadeOut:create(0.4),
                cc.CallFunc:create(function()
                    touchFeedback:setString("")
                    touchFeedback:setOpacity(255)
                end)
            ))
        end
    end)
    self:addChild(btn, 3)

    -- ── 1-second scheduler: update clock + date ─────────────────────────────
    local scheduler = director:getScheduler()
    self._clockTimer = scheduler:scheduleScriptFunc(function()
        local t = os.date("*t")
        clockLabel:setString(string.format("%02d:%02d:%02d", t.hour, t.min, t.sec))
        dateLabel:setString(os.date("%a, %d %b %Y"))
    end, 1.0, false)

    -- ── Listen for data events pushed from Android via Bridge ────────────────
    Bridge.onEvent("battery", function(payload)
        -- future: show battery level
    end)
    Bridge.onEvent("steps", function(payload)
        -- future: show step count
    end)
end

function WatchfaceScene:onExit()
    local scheduler = cc.Director:getInstance():getScheduler()
    if self._clockTimer then
        scheduler:unscheduleScriptEntry(self._clockTimer)
    end
end

return WatchfaceScene
