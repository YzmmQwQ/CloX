AOD implementation plan


# gesture control
implement a class or method to control swiping gesture to open overlay and app list, will be used later
when locked will colapse all overlay return to watchface, in fromt of watchface display a lock icon at top center

add detect double press in watch face only(check long press detect), in setting there was supposed to be option but is not working, default map to turn off screen, also change the default long press to open watch face selector
both long press and double press should have same list of actions to perform, one of them is lock viewpager, which that action will toggle between locked and unlocked, does not effect other things
so for now have following actions:nothing, turn screen off, watchface selector, open settings, lock gesture

# AOD
settings add a catagory for aod
main aod toggle which controls will make mainactivity keep screen on, default off
option to set timeout and brightness in aod, will listen for touches in mainactivity and refresh timer, when time reached go to watchface close all overlay and dim screen
option to detect screen off broadcast and re wake screen and start mainactivity if turn off from other app, default on
option to lock gesture in aod, default on, can set delay to lock, default 5s, when gesture is locked by AOD, doublepress always unlock the gesture set by AOD, when locked by AOD, watchface tap actions and long press action is disabled temporarely
option for shallow sleep, single touch wake up the screen but does not unlock gesture, only effective when above option enabled
option power button to exit AOD, when already in aod and screen off, will turn screen on without aod and unlock gesture
when aod active need to tell LUA to enable power saving mode

