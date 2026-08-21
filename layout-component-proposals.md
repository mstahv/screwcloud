# What this application had to build for itself

ScrewCloud is about as ordinary as a Vaadin application gets: two views on a server
(a list of devices, a dashboard of sensor cards), one view on a Raspberry Pi, cards,
a gauge, a sparkline, a couple of forms, and a theme. No data grid worth the name, no
navigation shell, no roles, no wizard.

Its UI is 3 421 lines of Java across 25 classes, plus 524 lines of CSS, and two
services whose only job is to get an update from the thread that has it to the page
that wants it. A large part
of both is not this application: it is scaffolding that every Vaadin application
builds again, or repair work on components that do not yet know about the theme they
are drawn in. This document lists what that was, with the file where the workaround
lives, and what could have shipped instead.

Written against Vaadin 25.2.5 with the Aura theme, `in.virit:gauge:1.0.0`,
`svg-visualizations:1.1.0` and `in.virit:viritin:3.8.0`.

The ranking at the end is the honest one: three of these would have saved more time
than all the others together.

---

## Part 1 — Components that could ship with Vaadin

### 1. A view root that scrolls correctly

**Status:** half implemented, by way of §13. `NavigationView` — the base class
the sub-views now extend — carries the min-height fix, so a view below the front
page gets it by inheritance and its paragraph of explanation lives in one place.
The front page itself still writes the line by hand: it has no reason to be a
NavigationView, which is the argument for the framework-level `AppView` below
remaining open.

**What we wrote.** Every view starts with `setSizeFull()`, because that is what the
archetype and every example does. It is wrong for any view whose content can exceed
one screen, and the failure is invisible until it isn't:

```java
setSizeFull();                 // the view's box is exactly the viewport
add(header, cards);            // the cards carry on past the bottom of that box
```

The layout's own bottom padding sits at the bottom of *its box* — one screenful down,
in the middle of the content — and the last card ends flush against the end of the
document. Measured on a phone-width page with three sensor cards: document height
1092 px, last card's bottom edge 1092 px, page padding 16 px sitting at y = 780. A
reader reports this as "the page looks like it's cut in two by a bug", which is a fair
description of what they can see.

The fix is one line different and nobody finds it by reasoning about flexbox:

```java
setWidthFull();
setMinHeight("100%");
```

It is in `DashboardView`, `DeviceListView` and pi-reader's `LocalView`, with a
paragraph of comment in each explaining why the obvious call is the wrong one.

**What could ship.** A view base class — `AppView`, `ViewFrame`, whatever it is
called — that gets this right and carries the other three decisions every view makes
anyway:

```java
public class DashboardView extends AppView {   // min-height:100%, theme page padding
    public DashboardView() {
        setContentMaxWidth("60rem");           // optional; centred when set
        add(...);
    }
}
```

If a base class is too heavy, the same result as a documented layout variant
(`VerticalLayout` + `theme="view"`), or simply as the thing `setSizeFull()` is
compared against in the docs. Right now `setSizeFull()` is the discoverable option and
it is a trap for exactly the case — a phone, a long page — where it hurts most.

### 2. A card grid that does not need media queries

**Status:** the front page now uses the proposed one-liner — a `Div` with
`repeat(auto-fill, minmax(min(15rem, 100%), 1fr))` in the stylesheet — and the
hand-computed 34rem breakpoint is gone. A phone gets one full-width card, every
other width gets a filled row. The finding below stands: this took a wrapping-row
detour and a breakpoint that shipped before landing on the one line.

**What we wrote.** Twice. `SensorCardLayout` is a `FlexLayout` with wrap, a gap token
and cards that are `setWidthFull()` with `setMaxWidth("35rem")`. The device list is a
`FlexLayout` with wrap, a gap token, and cards that were `setWidth("15rem")` — which
on a phone leaves a third of the row empty next to a card, because 15rem is a desktop
decision applied to a 390 px screen.

Fixing that properly meant moving the width out of Java entirely, since
`setWidth("15rem")` is an inline style and no media query can answer an inline style:

```java
addClassName("device-card");   // DeviceLinkCard
```

```css
vaadin-card.device-card { width: 15rem; }

@media (max-width: 34rem) {         /* where a second card stops fitting:      */
  vaadin-card.device-card {         /* 2 × 240px + 12 gap + 32 page margins    */
    width: 100%;                    /* = 524px, and 34rem is the step above it */
  }
}
```

Three numbers had to be worked out by hand to write that breakpoint, and they are
wrong the moment the gap token or the page padding changes.

**What could ship.** The CSS for this needs no breakpoints at all:

```css
grid-template-columns: repeat(auto-fill, minmax(min(15rem, 100%), 1fr));
```

That single line is the whole behaviour: as many columns as fit at 15rem or wider,
one full-width column when 15rem does not fit. Wrapped as a component:

```java
CardGrid grid = new CardGrid();
grid.setMinCardWidth("15rem");     // the only decision the application makes
grid.setMaxCardWidth("35rem");     // optional, for readable line length
grid.add(cards);
```

Both of our card layouts collapse to this, and neither application would contain a
media query. Cards are the format Vaadin has been pushing since `vaadin-card`
shipped; the layout that arranges them responsively is the missing half.

### 3. A section: heading plus content

**What we wrote.** Three of these on the front page, each one a `VerticalLayout` with
`setPadding(false)`, `setWidthFull()` and a heading, plus a private heading class
because the default `H2` is nearly the size of the brand name above it:

```java
private static class SectionHeading extends H2 {
    SectionHeading(String text) {
        super(text);
        getStyle().setFontSize("1.25rem");   // or there is no visible hierarchy
    }
}
```

Hand-setting a font size on a heading is a smell, and every application has this
class. It exists because heading sizes are absolute rather than relative to where the
heading sits: an `H1` for the application and an `H2` for a section inside it are one
step apart in the document outline and about a hair apart on screen.

**What could ship.**

```java
add(new Section("Devices", deviceCards));
add(new Section("Add a device", form, "The same 4 characters as DEVICE_ID in config.h"));
```

with the heading element chosen from nesting depth (a `Section` inside a `Section`
gets `h3`), the size following from the same depth, and padding/width already right.
The accessibility win comes free: correct heading levels are something applications
get wrong constantly, and here they would be structural rather than a choice.

### 4. An application header

**What we wrote.** `BrandHeader`: logo, name, tagline, source link. 91 lines, of
which the interesting part is that the obvious version looks bad and the reasons are
not obvious:

- `setSpacing(false)` on the text column — the default in most examples — puts the
  name, a three-line sentence and a link into what reads as one paragraph of unequal
  type. It needs a small gap, not none and not the standard one.
- `Alignment.CENTER` is right for a mark and one line and wrong here: on a phone the
  tagline wraps to three lines and the logo floats to the middle of them, opposite
  the words rather than beside the name it belongs to. `Alignment.START` is what was
  meant.
- The `H1` needs `margin: 0` or it pushes the logo out of line.

A user's summary of the before state: "melko tukkoinen" — quite cramped. That is a
correct read of a header built from the defaults.

**What could ship.** `AppHeader` with slots — mark, title, subtitle, actions — that
is top-aligned, gapped, and responsive by default. It is on the first screen of every
application anybody builds, and it is currently four components and three
non-obvious decisions.

### 5. An empty state

**What we wrote.** In two applications:

```java
private final Span emptyState = new Span("No devices yet — add one below.");
emptyState.getStyle().setColor(VaadinCssProps.TEXT_COLOR_SECONDARY.var());
...
emptyState.setVisible(devices.isEmpty());
```

**What could ship.** `EmptyState(icon, text, action)` as a component, and — more
useful — as a property of the layouts that can be empty:

```java
grid.setEmptyState(new EmptyState(VaadinIcon.PLUS, "No devices yet", addButton));
```

so that "is it empty" stops being a boolean the application has to keep in sync with
the list it describes.

### 6. Secondary text

**What we wrote.** Four times, in two applications, in three shapes:

```java
private static class Hint extends Span { ... TEXT_COLOR_SECONDARY ... }          // DeviceListView
private static class Reading extends Span { ... TEXT_COLOR_SECONDARY, BLOCK ... } // SensorCard
private static class SecondaryLine extends Span { ... }                           // pi-reader SensorCard
private void secondary(Span span) { ... }                                         // LocalView
```

Two of them also set `display: block`, because a `Span` in a card's content slot is
inline and two of them end up on the same line whenever the card is wide enough —
which is a bug we shipped and had to fix this week (see §9).

**What could ship.** Theme variants on `Span` — `SpanVariant.SECONDARY`,
`SpanVariant.CAPTION`, `SpanVariant.BLOCK` — or static factories on `Text`. The
token exists and is easy to reach thanks to Viritin's `VaadinCssProps`; the point is
that "this line is secondary" should not be a styling decision an application makes
one component at a time.

### 7. Relative time as a component

**What we wrote.** `Ages` (formats "2 min ago"), `TimeText` (absolute times as
"tomorrow 09:15"), `ClientTimeZone`, and `Elapsed` in the domain package because the
notifications share the wording — 174 lines together, all of it generic.

`ClientTimeZone` deserves its own mention: it is 65 lines and every one of them is a
pitfall we hit. `UI.getCurrent()` is null in tests and background threads. The
browser's zone id is null on browsers without `Intl`. The offset in the same details
object is not a usable fallback, because zero means both "genuinely UTC" and "not
filled in yet". A zone id the browser knows and the JVM's tzdata does not throws.

And the reason the dashboard polls every five seconds is partly that "2 min ago"
would otherwise go stale.

**What could ship.**

```java
add(new RelativeTime(measurement.receivedAt()));   // "2 min ago", updating itself
```

A component that formats with `Intl.RelativeTimeFormat` in the browser's own locale
and zone and re-renders on a timer client-side. No server round trip, no poll, no
formatter constants, correct in every language Vaadin claims to support.

**This one has an answer already, and that is the finding.** Flowing Code's
[RelativeTime add-on](https://github.com/FlowingCode/RelativeTime) is exactly the
component above — a wrapper around GitHub's `<relative-time>` element — and adopting
it took an afternoon: a dependency in two poms, five display sites changed, and the
`Ages` class plus two hand-written "is it minutes or hours" ladders deleted. It is
built against Vaadin 24 and works unchanged on 25, because all it uses is `@Tag`,
`@NpmPackage` and the Element API.

So the proposal is not "somebody should write this". It is that a timestamp on screen
is not an add-on-shaped problem: every application shows one, every application gets
the time zone or the wording or the staleness wrong on the way, and the fix is one
line once somebody knows the add-on exists. Ship it, or ship something like it.

What no add-on covered: `ClientTimeZone` should be one call —
`ui.getPage().getBrowserTimeZone()` returning a `ZoneId`, with the four fallbacks
handled inside Flow rather than rediscovered per application.

### 8. Polling and refresh

**What we wrote.** Twice, identically:

```java
@Override protected void onAttach(AttachEvent event) {
    super.onAttach(event);
    event.getUI().setPollInterval(POLL_INTERVAL_MS);
    pollRegistration = event.getUI().addPollListener(poll -> refresh());
    refresh();
}

@Override protected void onDetach(DetachEvent event) {
    if (pollRegistration != null) { pollRegistration.remove(); pollRegistration = null; }
    event.getUI().setPollInterval(-1);
    super.onDetach(event);
}
```

with a hand-rolled "has anything actually changed" check inside `refresh()` so the
poll does not rebuild the DOM every five seconds.

**What we did about it.** Deleted the polling and pushed instead — and then wrote
the same service twice, once per application, 331 lines between them. That is the
proposal: not a nicer poll, but the thing everybody writes when they stop polling.

The shape both ended up with:

```java
Registration forDevice(Component view, String deviceId, Runnable onChange);
void arrived(String deviceId);       // called from the thread that received the data
```

None of the interesting parts are about sensors, and all of them are the kind of
thing that is discovered rather than designed:

- **The handler has to change thread.** `UI.access` is the whole answer, and a
  handler that forgets it corrupts UI state from a background thread — slowly, and
  never the same way twice.
- **A subscription has to end with its page.** Ours removes itself on detach, so a
  view can subscribe in `onAttach` with no matching `onDetach`. Without that, every
  page view leaks an entry: invisible while developing, a slow leak on a server that
  stays up for months.
- **A closed browser is normal, not exceptional.** `UI.access` throws
  `UIDetachedException` for a page that went away between the event and the delivery;
  that is a signal to forget the subscriber, not to log an error.
- **One page must not take down the producer.** The thread calling this received
  every device's data and has nowhere to report to.
- **Bursts need collapsing** — and this is where the two applications had to differ.
  A device reports to the server every few minutes, so its version delivers every
  event. The Pi hears Ruuvi advertisements several times a second, so its version
  drops a wake-up when one is already queued: the pending refresh will read
  everything heard since it was asked for. That is four lines and an
  `AtomicBoolean`, and without it push is *more* work than the poll it replaced.
- **Subscribing before attach has to fail loudly.** There is no UI to push through
  yet, so the subscription is made, kept, and never delivers — a page that silently
  stops updating.

**What could ship.** That list, as a component-scoped event bus in Flow:

```java
// framework side
Registration subscribe(Component view, Class<T> event, Consumer<T> handler);   // ui.access, detach, coalescing
void publish(T event);
```

Vaadin's own documentation has taught the "Broadcaster" pattern for a decade — a
static list of `Consumer`s, a `ui.access` in the example, and every one of the
concerns above left as an exercise. The pattern being famous is the evidence that it
belongs in the framework rather than in the docs.

**And the test harness needs to know about it.** `UI.access` queues work for whoever
holds the session lock, which in a browserless test is the test itself for its whole
length — so nothing runs until `service.runPendingAccessTasks(session)` is called by
hand. Every test of pushed behaviour needs that line, it is not in any example, and
without it the assertion reads the state from before the event. One helper on the
test context (`ui.deliverPushes()`) would remove a discovery step from everybody who
tests this.

### 9. Card content should stack

**What we wrote.** A bug, then a fix. `vaadin-card`'s content part is
`display: block`, so children flow like text. Three items — a count, a badge, a
checkbox — stacked correctly while the card was 15rem wide, because two of them never
fit on a line. The moment the card became full width on a phone, the count and the
checkbox sat side by side, which is not a layout anybody chose. It only looked right
before by accident.

```java
VerticalLayout content = new VerticalLayout();   // DeviceLinkCard
content.setPadding(false);
content.setAlignItems(FlexComponent.Alignment.START);   // or a Badge stretches into a bar
content.getStyle().setGap(VaadinCssProps.GAP_XS.var());
```

**What could ship.** Either a stacking content slot by default — a card's content is
components, not prose — or a `CardVariant.STACKED_CONTENT`, or a documented
`--vaadin-card-content-display`. Whichever it is, "which line is this component on"
should not depend on how wide the card happens to be today.

While there: `--vaadin-card-media-aspect-ratio` exists (default `16/9`) and we did not
find it. We wrote

```css
vaadin-card[data-motif]::part(media) { aspect-ratio: 2 / 1; }
```

and only discovered the custom property later, in the compiled bundle. Media slot
sizing is a normal thing to want — 16:9 is right for a photograph and wrong for a
dial — so it deserves to be in the Java API (`card.setMediaAspectRatio("2/1")`) or at
least in the first paragraph of the docs.

Also, an opaque background on the media part covers the card's own outline along
three edges, because a child's background paints over its parent's border. We
recovered it with

```css
padding: 1px 1px 0;
background-clip: content-box;
```

which is a trick, not an API. A card with a cover-media slot should keep its outline.

### 10. Aura's dark background gradient is nearly flat

Not a component, but the finding that cost the most staring.

Aura derives the page background from `--aura-background-color`: a wash towards a
lighter, hue-rotated version of it plus a bright corner. The rotation is
`h + 180 * l * c * 4` — proportional to the colour's own lightness. For our light
background (`#D0DAFF`, l = 0.89) that is 33°, and the result is the best thing on the
page: pale blue sliding into lilac. For our dark background (`#0C0B2F`, l = 0.18) it
is 9°, and the result is a flat rectangle. Same theme, same tokens, one scheme
getting a tenth of the effect.

We ended up writing the dark scheme's gradient by hand, still derived from the same
token so there is one colour to change:

```css
--_sunset-far: oklch(from var(--aura-background-color-dark)
    calc(l + 0.10) calc(c * 1.35) calc(h + 38));
```

**Proposal:** the rotation and the lift should be scheme-aware rather than
lightness-proportional, or the dark derivation should use a fixed rotation. A theme
that looks designed in light mode and undesigned in dark mode is worse than one that
looks the same in both.

### 11. An overlay has to fit the screen it opens on

**What we wrote.** A settings form in a popover, and then three fixes to it, none of
which are about the form.

*It did not fit.* The form is 23rem wide, which is what its two limit rows need to
line up. A popover sizes itself to its content, so on a 390 px phone the form kept
its width, the overlay could not, and the right-hand end of every row sat behind a
horizontal scrollbar. The fix is arithmetic an application should not be doing:

```java
layout.setWidth("min(23rem, calc(100vw - 5rem))");   // 5rem = the overlay's own
                                                      // padding, plus its margin
                                                      // from the edge of the screen
```

*It was menu-padded.* `--vaadin-popover-padding` defaults to the small step, which is
right for a menu of three lines and cramped for a form with headings and hints.

*It was the wrong white.* The overlay takes a surface a step above the page, which
reads as raised on a dark scheme and as a hole on a light one — the page is already
pale and the fields inside are white, so the panel and the inputs were the same
colour and only the field outlines said where anything was.

*And the rows inside it did not wrap.* A text field, a number field and a button side
by side are wider than a phone. Turning wrapping on is one theme variant; making it
wrap *well* is not, because a row breaks wherever it runs out of room — which put a
range's two limits on different lines, separated by the dash that exists to say they
are one thing. What that needs is grouping: the pair in its own layout, the target
with the button that acts on it, so the break lands where a person would put it.

**What could ship.**

- **An overlay should not be wider than the viewport, ever.** Whatever the content
  asks for, the component knows the screen; clamping is not a decision an application
  has information for. Today the application has to write `100vw` arithmetic that
  includes the component's own padding.
- **Padding that follows the content.** A menu and a form want different amounts; one
  default cannot be right for both. A `theme="form"` variant, or padding that follows
  the overlay's own size, would remove the most common piece of overlay CSS.
- **Wrapping that respects grouping.** `HorizontalLayoutVariant.WRAP` exists and does
  the mechanical part. What every application then discovers is that flex wrapping has
  no idea which of its children belong together. A `nowrap` marker on a subgroup — or
  simply documenting "wrap in pairs, not in items" beside the variant — would save the
  discovery.
- **And the light scheme needs the same care as the dark one.** Raised surfaces are
  the place where a theme designed in dark mode shows it: lighter-than-the-page works
  until the page is already almost white.

### 12. The root background tiles on iOS

Aura paints the page background as

```css
background: var(--aura-app-background);
background-attachment: fixed;
background-size: 100vw 100vh;
```

with `background-repeat` at its default of `repeat`. `background-attachment: fixed`
is the one background property mobile Safari has never honoured. So on a phone, on
any page longer than one screen, the whole gradient starts again exactly 100vh down —
a visible horizontal seam mid-page. Three sensor cards is enough to scroll past it.
The user's words: "taustalla oleva liukuväri katkeaa nyt oudosti noin sivun kohdalla".

Our fix, which works in every browser:

```css
html { background: var(--aura-background-color); }

body::before {
  content: ""; position: fixed; inset: 0; z-index: -1;
  background: var(--aura-app-background);
  background-size: 100% 100%;
  background-repeat: no-repeat;
  pointer-events: none;
}
```

**Proposal:** ship that in the theme. A fixed-position layer is the standard
workaround and has been for a decade; `background-attachment: fixed` on the root is
not safe on the platform most of these pages are read on. At an absolute minimum,
`background-repeat: no-repeat` so the failure mode is a plain colour rather than a
seam.

### 13. A sub-view header: the way back, the name, and the actions

**What we wrote.** `SubViewHeader` plus 170 lines of CSS. A mobile-first
application with no navigation shell needs exactly one piece of chrome on every
view below the front page: a way up, the view's name, and a place for the view's
own actions. iOS settled the form years ago — a chevron in a circle of glass at
the left, the title in the middle, actions at the right, floating over the
content as it scrolls. Before this it was a `RouterLink("← Devices")` above an
`H2`, which works and looks like a document, not an application.

Three decisions in it were made by a user looking at screenshots, not by
reasoning, which is the point of recording them:

- **The arrow and the title must not share a container.** The obvious version —
  one glass pill holding both — reads as a single button whose label is the name
  of the view you are already on: a back button to the place you are at.
- **The title wears nothing.** Bare text beside a dressed control, as in iOS's
  current form language: the circle says "control", the undecorated word says
  "name".
- **A bare title floating over content needs a veil**, not a box: a
  `backdrop-filter` sheet behind the whole line, faded out at its bottom edge by
  a mask so the content dissolves upwards instead of hitting a border.

And two mechanical parts that took discovery:

- **Centring the title on the page's centre line** needs `grid-template-columns:
  1fr auto 1fr`, not flexbox centring — with a control on one side and nothing
  on the other, flex centres the title visibly off to the side.
- **Sticky top offsets must include the safe area.** As an installed PWA the
  viewport is the whole screen (Vaadin's index.html ships
  `viewport-fit=cover`), so `top: 0.5rem` parks the bar under the Dynamic
  Island the moment it sticks — in place while the page is at rest, untappable
  once scrolled, which is the worst kind of bug because it survives every
  desktop test. `top: calc(env(safe-area-inset-top, 0px) + 0.5rem)` is the
  whole fix, and it belongs in any component that ships `position: sticky`.
- **Aura's surface machinery only recomputes on a fixed selector list.** The
  glass is built from Aura's own tokens (`--aura-surface-color`,
  `--aura-overlay-backdrop-filter`, `--aura-overlay-outline-shadow`), but
  setting `--aura-surface-level` on an arbitrary element does nothing: the
  colour token is inherited already resolved against the page's own level. The
  escape hatch is the `aura-surface` class, which is in the recompute list —
  found by reading the compiled theme, not the documentation. Borrowing the
  overlay filter token rather than its values also borrows the theme's
  accessibility judgement: when the reader asks the OS for less transparency,
  Aura turns the blur off and raises the opacity, and the component follows
  without knowing it.

**What could ship.** A pair, roughly as written: the bar for pages that want to
compose, and around it `NavigationView` — a base class carrying the bar, a
`setTitle`, a re-aimable `setBackTarget` for parameterised routes, `addAction`,
and §1's min-height fix — for the common case that would rather inherit. Two
views here composed the same three lines before the base class collected them;
the name is Vaadin TouchKit's, which took it from the iOS of its day, and the
shape has not changed since. And whatever ships or not, `aura-surface` and the
surface-level recompute rule deserve a paragraph in Aura's documentation — it
is the difference between the theme's glass being material anyone can build
with and an internal.

---

## Part 2 — What the add-ons could do better

### Gauge (`in.virit:gauge`)

**Status:** implemented. 1.1.0 released empty-value support (`setValue(Double)`,
null draws an empty dial — pointer hidden, a dash for the reading), which deleted
the hide-the-gauge-show-a-dash dance from both applications' cards. The `main`
branch of the `./gauge-addon` checkout then takes the rest of this list: no
hardcoded background, dial text on `currentColor` with no shadow, the block/overflow
CSS shipped with the component, `HasSize`, a public `resetToDefaults()`, and the
traffic-light defaults replaced with a cold-to-hot ramp. Found on the way: the
inherited surefire was 2.17, so the project's tests had been silently reporting
"Tests run: 0" — and the React adapter turns a null state into the client-side
default, so emptiness travels as a boolean state of its own.

The original findings, for the record:

Our stylesheet contains 60 lines whose only job is to make this component belong to
the page it is on. Every one of them is a defect in the component, not a preference:

| What it does | What we had to write |
|---|---|
| Paints an opaque `rgb(40, 44, 52)` behind itself — a dark grey chosen for a dark grey page | `react-gauge, react-gauge > div { background: transparent !important }` |
| Draws the value as fixed white with a black `text-shadow`, **inline** | `g.value-text text { fill: currentColor !important; text-shadow: none !important }` |
| Draws the range labels as a fixed light grey, inline | `g.tick-value text { fill: currentColor !important; opacity: .7 }` |
| Clips its own SVG, slicing the range labels in half | `react-gauge > div > svg { overflow: visible }` |
| Grows to whatever width it is given — a saucer-sized dial on a wide card | `max-width: min(24rem, 84%)` |
| Fills the unreached arc with solid mid grey | `g.subArc + g.subArc path { fill: currentColor !important; opacity: .12 }` |

The `!important`s are the tell. Inline styles cannot be answered by a stylesheet, and
getting that wrong is not a no-op: the declaration that *does* land — usually an
opacity — then applies to the component's own hardcoded colour instead of the
theme's, which dims something already too light and leaves it less readable than
before the theme touched it. That happened twice while writing this file.

Specific asks:

1. **Nothing hardcoded and nothing inline.** `currentColor` for text and ticks, no
   background, no text-shadow. A component drawn on a card cannot know what colour
   the card is; `currentColor` is how it asks.
2. **The temperature defaults are a traffic light.** Green, yellow, orange, red,
   with everything above 20 °C in the last band — so an ordinary 22 °C living room
   glows red, because the fill takes the colour of the band the value falls in. Green
   also means "cold" rather than "fine". A thermometer wants a cold-to-hot ramp; we
   replaced it with blue → red in `TemperatureBandGauge`.
3. **`setupTemperatureDefaults()` is private and cannot be re-invoked.** Clearing an
   application's own bands therefore cannot restore the stock gauge, so we duplicated
   the defaults in application code and left a comment saying they will drift. A
   public `resetToDefaults()` closes that.
4. **No `HasSize`.** `ReactAdapterComponent` extends plain `Component`, so sizing goes
   through `getStyle().setWidth("100%")`. Every user of this component writes that
   line.
5. **No intrinsic aspect ratio exposed.** We had to discover empirically that a
   560 px wide gauge is 203 px tall in order to size the card's media slot around it.
6. **CSS parts.** The classes (`value-text`, `tick-value`, `subArc`) are what make any
   of this reachable at all — they should be documented as API, or promoted to
   `::part()`.

### SvgSparkLine (`svg-visualizations`)

1. **It strokes black.** On a dark page the last 24 hours were simply not there. This
   is a bug rather than a preference — `currentColor` for the curve, the guides and
   the labels, with CSS custom properties to override. We wrote
   `vaadin-card > svg path { stroke: var(--spark-line) }` plus two more rules for the
   guide lines and axis text.
2. **It draws outside its own box** (axis labels on the bottom edge, curve above the
   top) and the SVG clips them, so it needs `overflow: visible` — which then lets it
   escape the card, so the card has to clip. Either reserve the room in the viewBox or
   document the pair of rules.
3. **`draw()` has to be called by hand.** `setData()` only stores; on an attached
   component `draw()` then clears the data afterwards to save session memory. Miss the
   call and the curve updates on first attach and never again — which is the kind of
   bug that survives a demo and fails in production. Redraw on data change, or at
   minimum make `setData` on an attached component draw by itself.
4. **Sizing is a constructor viewBox plus `setWidthFull()`.** `new SvgSparkLine(400,
   100)` then `setWidthFull()` is two units of measurement in two lines; an aspect
   ratio and a CSS width would be one.
5. **"Not enough data" is left to the application.** Every user writes the same
   check — one point is a dot with two identical timestamps under it, which says less
   than nothing — so `setMinimumPoints(2)` and an optional placeholder belong in the
   component.

### Viritin

Viritin is the part of this stack that behaved. What it suggests:

1. **`VaadinCssProps` should be in Flow.** It is used 14 times in these two
   applications and it is the only reason the theme tokens are reachable from Java at
   all without string literals. Promote it, and add typed overloads so the `.var()`
   disappears: `getStyle().setGap(VaadinTokens.GAP_M)`.
2. **`PopoverButton` and `VDetails` with a lazy content supplier** are exactly right —
   the settings form and the measurement grid are built when opened and thrown away
   when closed, which is what makes one card per sensor affordable. Both patterns
   belong in core.
3. **`FormBinder` / `BeanValidationForm` are the reason the forms in this application
   are short.** One trap: `Composite`'s `Div` is size-full by default, so a form used
   as one section of a page pushes everything below it off screen until you write
   `getContent().setHeight(null)`. That trap and three related ones — the abstract
   `getFormComponents()` every custom-content form declines with an empty override,
   the layout half and the binding half being one class, and the default save button
   claiming ENTER for the whole page — are worked through with example code in
   `viritin-form-proposals.md`.
4. **`VSvg`** should inherit colour the way the sparkline needs (see above) — the fix
   probably belongs here rather than in each visualisation built on it.

---

## Part 3 — If only three of these shipped

1. **The view root (§1), the card grid (§2) and overlays that fit (§11).** Between
   them they are the whole "why does this look broken on a phone" category: content
   that runs off the end of its own padding, cards sized for a desktop sitting alone
   in a phone-width row, and a popover wider than the screen it opens on. All three
   were shipped bugs in this application, all three were reported by a user rather
   than found by us, and none of them is about this application at all.

2. **A theme-aware Gauge and SvgSparkLine.** 60 lines and 20 lines of our stylesheet
   respectively, all of it `!important`, all of it saying "please use the colour of
   the text next to you". Two components that ship with hardcoded colours cost more
   theme work than the entire rest of the application.

3. **`RelativeTime` (§7).** Not because it is hard — Flowing Code's add-on already
   does it, and this application now uses it — but because every application writes
   this itself first, gets the wording right, gets the time zone wrong, and polls the
   server to keep a label current. The component being an add-on is what makes that
   the default path.

The pattern behind all three: the components exist, and what is missing is the layer
where somebody has already decided how they sit together. That layer is what an
application developer with no interest in typography — which should be most of them —
needs to be able to skip.
