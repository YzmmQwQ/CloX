Watchface handling implementation plan

all UI added here must follow modern material design 3 standard(minus settings)

# Watch face selector
## gesture
at settings gesture section add option for long press, and for now can only choose nothing or open watchface selector
watchface selector should have a launcher intent entry
## file structure
in external files DIR create a folder to store the watch faces
watchface is .watch file, it is zip archive, preview is in archive root preview.jpg
example at /Downloads/wf_314_jb.watch
allow to create one level subfolder to organize the watch faces
absolute path of watch face is passed to lua to load it

## UI
in settings add a catagory for watch face, whewre you can choose to use two different selector, a option to choose sort order by file name or by last modify time

the first one is horizontal view pager, card of watchface preview in middle, on top is file name minus extension, bottom is action buttons, delete, rename, pin/unpin, recycler view have detent always snap to one or the other watch face, the previous and next card animate to smaller 
pinned one appear at front of list
the current selected one needs a indication like a colored frame
subfolder is disregarded in mode 1
at very start of the list is a position with 4 button, open watchface store, enable remote management, import watchface from storage, choose random watchface, second option to select default watch face (preview put app icon for now)

second one is vertical scroll 
at top is the tool bar, option to open watchface store, remote management, import, choose random watchface, search
when search pressed the rest 4 button becomes search bar, and search button becomes X when press again close search bar
need to add margin at 2 side of actionbar to avoid rounded corner
watchface image and with name below as a card, 2 wide vertical recycler view, full width span bar act as folder indicator, colapseable which will colapse all watch face inside that folder, folder name and content count is displayed on the bar
long press watch face can pin/unpin, delete, rename, move(select dest folder)
long press folder can delete, rename, move all content
on top is the "Pinned" vertrual volder
after all subfolder is the "Other" vertrual folder which is the root of directory
on bottom have the add new folder button, default watchface  

a class gesturecontroller should be created to manage gesture
when starting watchface selector from mainactivity, do animation where the watchface zoom down into the preview in the card(when open it always scroll default to the current active watchface), when selected, do animation the preview image zoom to full screen and cut to real watchface

# remote management
a simple web server to manage the watch face, UI should be useable on both phone and PC, static web page, use API to do file operations, should support folder management rename upload download delete batch upload, multiselect and move delete, a button on top get more watchface opens 
https://jbwatchface.ai-life.xyz/

# watchface store
backend read 
https://gitlab.com/ailife8881/watchmaker-community-store/-/raw/main/API_DOWNLOAD.md?ref_type=heads
frontend same 2 wide vertical scroller always, minimalist, downloaded one detect by file name show indicator on card, search bar on top, card show preview, name, download count, after press animation zoom in and display detail with download button, after download close downloader automatically set as active, animate to mainactivity

# Install from intent
add a intent can open .watch file, when a file is opened with it try to copy the watch face to the watch face directory root and set as active imidiately go to mainactivity

# batch import watchfaces
UI to select watch face, try to use SAF to let user either select a folder or multiple or one file, file is recursively copied into watchface directory, in background thread, show UI progress bar, after import go to selector

# programatic access
i need a way to grant persistent read write access to the watch face folder to other app

watchface store, remote management, import watchface option is to be added in settings
after watchface changed, java need to call lua method to set the active watch face path