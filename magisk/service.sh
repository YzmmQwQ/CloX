#!/system/bin/sh
# CloX priv-app module — late_start service (runs after boot completes).
#
# Path A needs the launcher to call non-SDK framework internals (RemoteAnimationAdapter,
# ActivityOptions.makeRemoteAnimation, Activity.registerRemoteAnimations, SurfaceControl.Transaction).
# On API 30 those are blocked by hidden-API enforcement for non-platform-signed apps. Disabling
# enforcement globally lets CloX reach them. hidden_api_policy is a "global" setting (not a prop),
# so re-assert it on every boot.
#
#   0 = disabled entirely   1 = enabled, no enforcement (allow + log)
# We use 1 so any real blacklist hit is still logged, while nothing is blocked.

# Wait for the settings provider / boot to be ready before writing global settings.
until [ "$(getprop sys.boot_completed)" = "1" ]; do
  sleep 2
done
# A little extra grace for the settings provider to come up.
sleep 5

settings put global hidden_api_policy 1
settings put global hidden_api_policy_pre_p_apps 1
settings put global hidden_api_policy_p_apps 1

log -t CloXMagisk "hidden_api_policy set to $(settings get global hidden_api_policy)"
