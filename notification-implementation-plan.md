Notification implementation plan

all UI added must follow modern material design 3

slide right the notification panel open from the left edge
need to fix the gesture controller to only intercept swipe left to colapse it, swipe other direction need to pass to child
a background color layer is not needed, the gesture already used blur and darken effect to make the content above visible

two view style is available, one is list view, other is page view

## list view
notifications display as material card view, rounded corner, darkened semi translarent background, with stroke around the card

when have music playing check media session the first card will be pinned to the player, android 16 style, cover art cut to card shape, top left is the icon, top right open the volume panel from QS, tiotle and artist below align left, play pause button on right with colored background rounded square, below is progress bar, support seeking, when playing have wavy effect, below the bar is the buttons previous, close, loop mode, next

normal notification display app icon top left, app name to the right and the send time
below is the title and content, text size should not be too small to be able to view on watch
limit the number of lines by default, top right the colapse open button allow to expand to full notification
large icon show in place of the app icon, app icon scale down show in the buttom right corner of large icon insted
after expand show full app icon on top, big picture show below app icon in same size, with transition animation for expand cloapse
if have big picture show in the exbanded view
chat notification show the same, but try to get group or user icon to show in the large icon way, app icon to the user icon bottom right
if notification have action buttons also need to get it, show in expanded view same way as in native android, if have reply action after press button open a dialog for you to type reply, then send the reply text to app
swipe right on each notification card to dismiss, dismiss it from system too, if dismissed in system also clear in app
bottom right corner is dismiss all floating action button
## page view
vertical viewpager, one notification per page, show as much can be displayed in one page, larger text, show more button on bottom of each page like wear os, press show all the content like the image and the action buttons as horizontal full span material rounded pill shape button stacked vertically
one when scroll to the end, can swipe further to change page, after change page always make it colapsed state, so can swipe easilly to the one want to see and expand it

swipe to the -1 position(swipe down on the first notification top go up again) to go to the page with option to dismiss all notifications

# backend
if notification permission granted but service not running try to restart it, also do for accessibility, add a fixsevices method as in watch it sometimes dies

in first open notification access is not granded, when user swipe right open notification panel, now detect the android version and is low ram device or not, if <= android 10 and is low ram, show warning device do not support notification feature, with layge button "view notifications", when pressed will open 
ComponentName("com.android.settings", "com.android.settings.Settings$NotificationStationActivity"), with small button to try anyways which will try to let user grant notification (but user most likely see this feature is not available on device)
if not low ram or > android 10 then the warning show permission required and large button to go grant, take to settings, if grsanted then can enable to be useable

on app start get all existing notifications, and use listener to listen for new ones

need to connect the api to the watchface bridge to receive last notification contnt and the notification count, if the listener is not running, return -1 notification count to watch face

# setings

option to enable disable notifications(disable will not allow gesture detector to open overlay just like QS, stop all backend to save power), option to change text size in notification, change notification style list or pages, enable or disable media control
option to wake screen, open notification panel, vibrate on new notification, default all off
option to group notification or not(group chat to top, normal in middle, persistent to bottom)
option to block persistant notification at all
option to block notification from some app(in notification list, when user long press, show context menu block from this app or open settings, when open settings open the android setting of that app notification setting page)(when blocked will just be ignored from launcher and left untouched in system)