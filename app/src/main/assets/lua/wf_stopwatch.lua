-- wf_stopwatch.lua — shared stopwatch state behind the {sw*} tags and the
-- sw_start_stop / sw_reset tap actions (UL WatchSkin semantics: one global
-- stopwatch per process, state survives face switches).
--
-- Time source is the engine clock's `elapsed` (seconds since face load,
-- monotonic within a face). startAt is rebased on every start so pausing and
-- resuming accumulates correctly even across face switches (a new face's
-- elapsed restarts at 0, but startAt is recomputed from stoppedAt on the
-- next start; a RUNNING stopwatch carried across a face switch is rebased by
-- wf_render on load).

local SW = { running = false, startAt = 0, stoppedAt = 0 }

-- Elapsed stopwatch time in MILLISECONDS at engine time `now` (seconds).
function SW.durationMs(now)
    local d = SW.running and (now - SW.startAt) or SW.stoppedAt
    if d < 0 then d = 0 end
    return d * 1000
end

function SW.startStop(now)
    if SW.running then
        SW.stoppedAt = now - SW.startAt
        SW.running = false
    else
        SW.startAt = now - SW.stoppedAt
        SW.running = true
    end
end

function SW.reset()
    SW.running = false
    SW.startAt, SW.stoppedAt = 0, 0
end

-- A face switch resets the engine clock's elapsed to 0; a running stopwatch
-- must keep its accumulated time. Called by wf_render on load with the OLD
-- clock's final elapsed: startAt becomes negative by the accumulated amount,
-- so duration keeps counting seamlessly on the new clock. A stopped watch
-- needs nothing (stoppedAt already holds the accumulated time).
function SW.rebase(oldNow)
    if SW.running then
        SW.startAt = -(oldNow - SW.startAt)
    end
end

return SW
