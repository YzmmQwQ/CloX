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

# Samsung One UI (verified on SM-A5360 / Android 14) IGNORES hidden_api_policy for app
# processes — CloX still ran with hiddenApiEnforcementPolicy=2 and every blocklisted member
# (IRemoteTransition$Stub.<init>, ActivityTaskManager.getService) threw NoSuchMethod, killing
# both morphs. The exemptions list IS honored there: "L" prefix-matches every class descriptor,
# so nothing is blocked. Persistent global setting; re-asserted each boot like the policy.
before="$(settings get global hidden_api_blacklist_exemptions)"
settings put global hidden_api_blacklist_exemptions "L"
# The exemptions only reach processes spawned after they're set. CloX (the HOME app) is already
# running by late_start, so on the boot where the value actually changed (first boot after
# flash/upgrade), restart it once so it picks them up. Later boots read the persisted value at
# launcher spawn, so no restart is needed — and none happens.
if [ "$before" != "L" ]; then
  am force-stop com.ailife.clox
  log -t CloXMagisk "hidden_api_blacklist_exemptions set — restarted CloX once"
fi

log -t CloXMagisk "hidden_api_policy=$(settings get global hidden_api_policy) exemptions=$(settings get global hidden_api_blacklist_exemptions)"
