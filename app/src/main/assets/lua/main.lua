-- CloX Launcher — Axmol/Lua entry point
-- Called by ax::Application on GL thread start

require "watchface"

local function main()
    local scene = WatchfaceScene:create()
    cc.Director:getInstance():runWithScene(scene)
end

main()
