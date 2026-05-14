i want to add the bridge layer in between LUA and java
LUA need to synchronize data to display on watchface from java
there are some main catagorys

1. LUA initiate action to java only

tapaction: 1 string parameter, watchface shortcut action

2. load into LUA once and forget

* Android API level (e.g., 30 for Android 11)
* System language code (e.g., "en", "zh")
* Device name/user label
* Device model identifier
* Manufacturer name
* Watch product name (return string "CloX Launcher")
* OS type string (return string "Full Android")
* App version (e.g., "4.1.0")

3. data that need to be synchronized on first start, but need to be updated later when value changed

* selected watchface path
* screen zoom, xoffset, yoffset, fullscreen mode(int), frame rate, AOD mode enable
* 5 location name + 4 timezone
* battery level, charging or not, battery temperature
* notification count
* last 10 calendar event(any event exist?, (event n exist,Event ID,Title,Begin time (HH:MM),End time (HH:MM),Begin date (YYYY-MM-DD),End date (YYYY-MM-DD),All day event,Location,Color (hex RRGGBB),Calendar name)x10)
* wifi enable, wifi signal strength, wifi ip address
* data enable, data signal strength, data type(3g 4g)
* bluetooth enable, gps enable
* total storage, used storage, total ram, used ram
* have alarm(within 24hr), alarm time HH:mm
(some of them should update periodically, not too often, 5 or 10 second is enough, for data that do not change often, update using observer)

4. data that need to be loaded async in java, java run a update loop when showing watch face, if current time and last update is more then interval do a update, after receive send to LUA

* weather : the structure of data to be send will provide later
* health : step(+calorie, distance), heart rate, blood oxygen (each of the 3 need to send separately because sensor take long time to measure)
beacause these data take significant performance to get, lua should have method to enable/disable the update

5. java call LUA function
* set active watch face (path parameter)
* reload current watchface(destroy and reload watchface app)
* reset current watch face(delete persistent storage+reload)

5. special (sensors)
* accelrometer(seems like axmol already support natively, if not add it)
* compass
* barometer
they should be enabled and disabled from LUA depends on if it is needed in watch face, when enabled then java send the value to lua when sensor onchanged 


## weather data
* current temp, today high, today low
* condition text, condition icon number
* humidity, pressure, wind speed, wind direction degree, cloudiness percent, rain volume
* is daytime, sunrise, sunset, moonphase
* last update time
* (hour n temp, condition text, condition icon)x12
* (day n temp avg, high, low, condition text, condition icon)x5


# implementation stratagy

use the best practice possible for performance even if it takes a bit longer
the data should be structured well in LUA bridge so it can be get easilly in the watch face engine which will be written in the future, it is not done yet
in java, create helper file for the data, for things like weather and health where the backend do not exist yet, add method placeholder that will call the data source to update, also the one data source will reply with , should include comment how data should be structured, or try to use a class for it so it is even more readable