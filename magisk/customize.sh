#!/system/bin/sh
# CloX priv-app module installer hook (sourced by Magisk's update-binary).

ui_print "- Installing CloX as /system/priv-app"

if [ ! -f "$MODPATH/system/priv-app/CloX/CloX.apk" ]; then
  ui_print "! CloX.apk missing from the module — repackage with build-magisk-module.sh"
  abort "! Aborting: no APK bundled"
fi

# Magic-mount overlay files: standard system contexts. APK 0644, dirs 0755.
set_perm_recursive "$MODPATH/system/priv-app/CloX" 0 0 0755 0644
set_perm "$MODPATH/system/etc/permissions/privapp-permissions-com.ailife.clox.xml" 0 0 0644

ui_print "- Hidden-API enforcement will be disabled on each boot (service.sh)"
ui_print "- Reboot to activate. CloX will hold CONTROL_REMOTE_APP_TRANSITION_ANIMATIONS."
