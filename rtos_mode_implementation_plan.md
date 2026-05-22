implement a mode that will disguise the android watch as RTOS bluetooth watch
two mode is available

1. only show watchface
2. fake RTOS system

when either is activated, in watch face, touch is not passed to LUA
have two type of password, keypad and morse code

keypad:
a invisible keypad arranged
123
456
789
on the watch face, divided screen into 3x3 grid

morse code:
long/short press on watchface anywhere to input code

the password is matched by sliding window every time there is a new touch it try to match again
after unlocked send a toast exit RTOS mode, and unlock device

mode 1 will just only show watch face, all touch intercepted, disable all gesture(long press/double press/action button), swipe gesture locked, disallow all system window, disallow all app launch

mode 2

disable swipe up(widgets)

swipe right open notification, always just show "no notifications" text, do not show notification or player

swipe down QS, large pill shaped button, grey and blue on black background, white icon no text
add following tiles: bluetooth(only enable/disable, no long press),battery(just a white large text displaying xx% inside pill), sound(toggle sound and vibrate mode), brightness(tap change 5 step), settings, QR code

double press always turn screen off, long press always open watch face selector in pager mode, and hide first card(import, store etc)

app list show allowed apps(configureable in settings), default show all system app only, remove the apps that have browser, camera, settings in package name and app name, but can overwrite in settings yo show or hide more, hide own launcher settings, long press is disabled for context menu and reorder

do not allow to start app not in white list, if detected go home


make a fake settings app(disable launcher shortcut untill activated), show the setting options typically found in RTOS watches, design also follow LVGL style as used in RTOS, doesnt need to be functional, and add a qr code page that just show a random mac address and doesnt do anything but for look(add in settings option to show too), for fake settings just try to make development easy, compose can be used if it is faster, maintaibability also not in consideration, this entrence only show in rtos mode active


in the app setting, first add option to switch watchface only or full RTOS, option to enable accessibility to stop other app launch and disable system window, will check permission before allow enable
add option to display a launcher icon to start it
add option to auto enter when screen off
option to change password type
option to set password, first a screen to explain  how it works, how it unlocks(as there is no cue at all user must read the instruction and trust the process), need to wait 5 second to get pass, then a screen in full screen, if in password mode have the grid line showing allow user to input twice to confirm, like how they will unlock it same format


add in the gesture list(for long/short press, make share one list) on of them start rtos mode
add a QS tile to start rtos mode not added by default can add in settings

try to contain the code of this feature as much as possible and dont cause difficulty or future bug in other part of code which needs to be maintainable