widget/shortcut implementation plan

when swipe up from bottom edge the widget drawer open
no additional background is needed by the ovewrlay, blur and darken effect is already in gesture control layer

all UI added must follow material design 3 modern design language

# UI
widgets is displayed in material card view inside recycler view
there are two kinds of card

## full width card
user can adjust the height of widget to 1,2,3,4 (4 is square, width height 1:1)
directly display the widget card, apply rounded corner

## single cell
a system card is added with 3 position horizontally, each of them user can choose from
* appwidget
* a page to select any exported activity from a app
* app shortcut (can support added programatically by other app)

at the end of the list, a pill shaped edit button, after press you can long press reorder the list, a delete button appear at top right of each card (for single cell all 3 can delete seperately, or delete entiire large card), at the end of list appear empty card outline with + to add card, all empty position of single call have + too to add another small icon inside
when + pressed to add card, selection to choose from add appwidget or add launcher built in card or add intermidiate card which is the 3 cell one
launcher built in card have the following
* digital clock on left with date and day of week below, on right is simple analog clock
* weather (use available data from weather provider for watch face), on top display location and the current weather, below display 5 day condition in icon and temperature

