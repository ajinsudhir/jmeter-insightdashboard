# Changelog and design notes

This is the project's running development log, newest entries first, kept verbatim from the original README. For an overview, see [README.md](README.md).

---

# Insight Dashboard for JMeter

<img src="docs/logo.svg" alt="Insight Dashboard logo" width="96" height="96" align="right"/>

A real-time, collapsible per-transaction results listener for JMeter.
Live stat cards, response-time charts, and a tree table you can expand
per transaction to see its individual requests, SLA threshold
highlighting, throughput/P90/P95/P99, and CSV/Excel export — plus an
optional AI-assisted Results Summary and Chat tab (via a local Gemini CLI
install) that writes a performance analysis and answers follow-up
questions about the current test plan and results. The AI layer is
entirely optional; everything else is computed directly from the test
data, no network calls involved.

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

## Results Summary (AI): data tables could go blank after the AI response came back, even though the AI's own analysis was correct

Reported directly, with a screenshot: after "Enhance Observations with AI" finished
successfully (status showed "Done", not an error), sections 1-3 (Top 10 Slowest
Transactions/Requests, Failed Transactions, error codes) showed "None" - while the AI's
own written analysis, right below them, correctly described real transactions with real
numbers. The AI had clearly analyzed real data; the sections displayed alongside its
response just no longer matched it.

**Root cause:** sections 1-4 were being computed from live data twice - once implicitly
(via `buildReportText()`, which feeds the AI prompt from the live table's current state)
when the button is clicked, and once again from `collectTransactionSnapshots()` /
`collectRequestSnapshots()` when the AI's response comes back and the report re-renders.
Those are two separate reads of live state, taken at two different moments, normally in
sync - but an AI call can take anywhere from several seconds to its full timeout, and
whatever changed about the live data in between that gap (a different row selected, a
JTL reloaded, anything else) meant the second read no longer matched the first. The
existing "Discarded - test was re-run while waiting" guard (`dataGeneration` tracking)
only catches a full Run; it was never meant to catch every way the live data can shift
in the meantime, and this wasn't one of them.

**Fix:** sections 1-4 are now captured once, as a single HTML snapshot, at the exact
moment "Enhance Observations with AI" is clicked - alongside the report text already
being built for the AI prompt at that point. When the response comes back, that same
frozen snapshot is reused rather than re-querying live data a second time, so whatever's
displayed is guaranteed to describe the exact same data the AI actually analyzed, not
just whatever live data happened to exist when its response arrived. Every other caller
(Reload Data, Clear Observations, the automatic populate when a test ends) still
captures a fresh snapshot at its own call time, since "right now" is the correct,
intended behavior for those.

## Per-request stats: child-row prefix stripping - still showing the full name, now actually fixed everywhere

Reported back as unchanged after the first attempt. The display logic
itself was correct, but it turned out to be duplicated across **three
separate places** in this file, and the first fix only updated one of
them (the live table's own cell renderer) - leaving the other two
untouched: the column's auto-fit-width calculation (which measured the
full, unstripped label), and the plain-text "Results Summary"
generator (which built its own copy of the same prefix logic
independently). Since the table you were actually looking at renders
through the cell renderer specifically, that one *was* fixed - but if
this was tested via a build from before that change, or if the summary
text or auto-sized column width was being compared instead, the
duplication would make it look like nothing had changed at all.

Consolidated all three into one shared method instead of three
independent copies, so they can't drift out of sync with each other
again going forward - this is also exactly the kind of bug duplicated
logic invites, so collapsing it to one place is worth doing for its
own sake, not just to fix this specific report.

## Per-request stats: child rows no longer repeat their parent's name

Reported directly from a screenshot: a child row's label routinely
repeats its parent transaction's full name as a literal prefix (e.g.
`S01_Collector_Activity_T01_LoginPage 054 /configurationmgmt/...`),
which the tree's own indentation already makes obvious - wasted space
in an already-narrow column, at the expense of the part that actually
distinguishes one child from its siblings (the URL).

Child rows now show just that distinguishing remainder (`054
/configurationmgmt/...`) when their label starts with their parent's
own label - display-only, so sorting, filtering, CSV/Excel export, and
everything else still see the full, original label untouched. A row
whose label doesn't actually start with its parent's (a different
naming convention) is left exactly as it was, rather than silently
hiding real content on an assumption that doesn't hold. The full,
original label is still available as a tooltip on hover, for anyone
cross-referencing against a raw .jtl file.

## Render HTML: still blank after the font-family fix - a second, separate cause found

Reported back with the same page still blank after the previous fix.
Re-examined the actual attached document rather than assuming the
first fix was simply incomplete, and found a second, genuinely
separate structural problem: a stray footer `<div>` and `<script>`
block sitting entirely *after* the page's own closing `</html>` tag -
confirmed directly by searching for it (212+ characters of real markup
past that point). That's invalid document structure on its own,
independent of the font-family quote issue, and can derail Swing's
fragile parser just as badly.

Rather than continuing to chase individual malformed patterns one at a
time - a real risk of still missing some third issue on some other
page - switched to a more general-purpose fix: extract just the
`<body>...</body>` inner content and discard everything else (the
`<head>` section, any DOCTYPE declaration, and critically anything
after the first closing `</body>` tag) before applying the font-family
fix and handing the result to the renderer. This sidesteps several
potential parsing landmines at once - multiple external stylesheets
Swing can't load anyway, a DOCTYPE it doesn't need, and any trailing
malformed content - rather than needing to specifically identify and
sanitize each one. Verified end to end against the actual structure of
the attached page before shipping.

## Render HTML showing nothing, for certain real pages

Reported directly with the actual response body attached - a Keycloak
login page. Found a concrete, identifiable cause rather than a vague
"HTML is hard": the page's markup has a genuinely invalid pattern,
repeated twice -

```html
style="font-weight: 600;font-family: "Open Sans", Helvetica, Arial, sans-serif;"
```

The outer, double-quoted `style` attribute contains an *unescaped
double quote* around the font name - invalid HTML. Swing's
`HTMLEditorKit` is an old, fragile parser, and markup like this can
confuse it badly enough to render nothing at all rather than degrading
gracefully. Fixed in two layers: this one specific, surprisingly
common real-world pattern is now sanitized before rendering (the inner
quotes become single quotes - equally valid CSS, and it fixes the
attribute without needing a full HTML parser to do it safely), and as
a safety net for anything else, a parse failure now shows a clear
message ("try Raw or Pretty instead") rather than a silently blank
pane that looks like the feature itself is broken. Lives in the one
shared `ResponseViewerPanel` class used by both the Live Dashboard's
and the Failures tab's Response Data panels, so both benefit.

## Response Data panel: mouse scrolling was slow, and sometimes stuck

Reported directly: scrolling felt slow, or only worked near the edges.
Traced to two real, concrete bugs, both in how mouse wheel events were
(or weren't) being handled:

1. This specific panel's two scroll panes (Raw/Pretty text, and the
   new Render HTML view) had **no** mouse-wheel handling installed at
   all, unlike every other HTML/text pane in this plugin - left
   entirely to Swing's own default behavior, which for a `JEditorPane`
   rendering HTML is long-documented to sometimes swallow wheel events
   without forwarding them to the enclosing scroll pane at all.
2. Even where a custom handler *was* installed elsewhere in this
   plugin, it never set an explicit scroll-amount (so it fell back to
   Swing's small default, each wheel notch moving only a few pixels -
   "slow"), and never marked the event as handled, meaning it could
   still also reach the viewport's own default handling underneath and
   the two fight over the same scrollbar - a plausible explanation for
   scrolling that felt like it only worked "at the edges", since which
   of the two wins can depend on exactly where over the component the
   pointer lands.

Fixed both at once: the shared mouse-wheel helper now sets a proper
scroll amount and marks the event handled once processed, and this
panel's two scroll panes now actually use it, which they never did
before.

## Create Correlation Rule: "Template (project)" now defaults and offers existing ones

Was a plain text field the user had to retype (or remember exactly)
every time. Now an editable dropdown: pre-populated with every distinct
project name already in the template store
(`~/.jmeter-auto-correlator/templates.json`), defaulting automatically
to whichever one was most recently used - so the common case (adding
another rule to the project just worked on) needs no typing at all.
Picking a different existing project, or typing a brand new name to
start a fresh one, both still work exactly as before.

Reads the field's text through its own editor component rather than
plain `getSelectedItem()` - for an editable combo box, that can lag
behind something just typed but not yet committed (by pressing Enter
or tabbing out), the same kind of uncommitted-edit gotcha handled for
the Test Data Generator's column table earlier.

## Request/Response Data: bounded height instead of stretching to fill

Reported directly from a screenshot where a short, 2-line JSON error response was sitting
in a text box that stretched to the full height of the detail panel, leaving a large
empty gap below it. The follow-up explanation clarified the actual concern: for *long*
content, that same stretch-to-fill behavior meant the whole panel - including its
Headers/Body/Cookies tabs and the Find bar above the content - had to grow taller than
the window to show everything, pushing those controls out of view and requiring a
scroll back up just to switch tabs or search again.

All six text views (Headers, Body, and Cookies, on both the Request and Response Data
tabs) now have a bounded, modest default height instead - about 16 lines - with their own
internal scrollbar for whatever doesn't fit. Short content no longer forces the box to
be any taller than it needs; long content scrolls within its own fixed-size area, so the
tabs and Find bar above it stay in a fixed, nearby position regardless of how long the
response gets.

## Two real bugs found from a correlation-extractor report

Reported with a full chain of screenshots - a regex extractor ("Extract client_id") and
its manually-tested regex both looked correct, but the Extracted Variables tab showed "no
variables appear to have been newly set or changed", making it look like extraction had
failed when it hadn't.

**The actual bug:** on the very first sample of a thread, `diffThreadVariables()` had
nothing to compare the current variables against, and was returning an empty map in that
case - silently discarding every variable, even ones genuinely set for the first time on
that very sample. Every variable present on a thread's first sample is, by definition,
newly set (there was nothing before it), so this now returns the full current set in that
case instead of nothing. The explanation text in that tab is updated to match - the "empty
on the first sample" caveat no longer applies, since the first sample's variables are now
shown directly rather than hidden.

## Per-request stats: sub-requests were always sorted by response time, ignoring Sort by

Also reported in the same batch: a transaction's sub-requests weren't appearing in a
sensible order. Found a second, genuinely separate bug: the Sort by dropdown and the
Asc/Desc toggle only ever affected the order of top-level, parent rows - sub-requests
within an expanded transaction were hardcoded to always sort by average response time,
descending, no matter what was actually selected. In the reported screenshot this happened
to coincide with the dropdown already being set to Avg (ms) descending, which is why it
wasn't obviously broken from the controls' own state - but changing the sort key or
toggling to ascending would have made no visible difference to the sub-requests at all.

Rather than make children merely respect the same sort control (which would still scramble
their actual execution order whenever "Avg" or anything else was picked), sub-requests
now sort by their own `firstTimestamp` - already tracked per label, the earliest actual
request start time seen for it - reproducing the order they genuinely ran in within that
transaction. That's a different, and more useful, question than "rank these parent
transactions against each other", which is what the Sort by control is actually for.

## Find/search added to Request and Response Data

Requested directly for a long response body (a full HTML page's markup, in the screenshot
that prompted this). Every text view across both Request and Response Data - Headers,
Body, and Cookies, on both tabs - now has a small **Find:** bar above it: type a term,
press Enter or click Find Next/Find Previous, and the match is selected and scrolled into
view, wrapping around at either end once it runs out of matches in that direction.

The Body sub-tab's find bar is a little more capable than the others, since Body can show
either the plain Raw/Pretty text or the rendered HTML view depending on which mode is
selected - it searches whichever of the two is actually visible at the moment, rather than
being tied to one fixed component. One nuance worth knowing: in Render HTML mode, it
searches the underlying HTML source (tags and all), not just the rendered, visible text -
the same content Raw and Pretty already show, just displayed differently.

## Find Dynamic Values: a few more field names recognized

Asked about directly: a JWT-shaped value showing as `(unnamed)` with
**Create Correlation Rule...** disabled. That part is deliberate, existing behavior -
explained in the tooltip already there - a reusable regex extractor needs an actual field
name to anchor to, and a value found by the "possible token" fallback scan (which just
looks for long, random-looking strings with no attempt to find a nearby name at all) has
none to offer.

What *was* worth fixing: the "named field" scan only recognizes names in a fixed
dictionary, and a handful of real Keycloak cookie names confirmed directly from actual
response headers seen this session - `AUTH_SESSION_ID`, `KC_RESTART`,
`KC_AUTH_SESSION_HASH` - weren't in it, so a token sitting in one of those cookies fell
through to the unnamed fallback even though it genuinely did have a name to anchor on.
Added those three, plus a few more common OAuth/OIDC names. A value in one of these now
shows as a proper "Named field" finding, with **Create Correlation Rule...** enabled.

## Hide/Show Detail Panel moved to the top; the tiny divider arrow is gone

Reported directly from a screenshot: the split pane's own built-in one-touch-expand
arrow (a small triangle embedded in the thin divider between the table and the detail
panel) was hard to click reliably. Rather than try to make that tiny arrow bigger,
removed it entirely - the existing **Hide/Show Detail Panel** button already does the
exact same thing without that problem, so keeping both just meant two controls for one
action, one of which was genuinely fiddly to use.

That button has also moved - from the detail panel's own header, far to the right of the
screen, up to the top controls row, right alongside **More Options**. Previously finding
it meant the eye had to travel across the whole width of the table before reaching it;
now it's in the same immediate view as the rest of the panel's everyday controls.

## Request and Response Data: now split into Headers / Body / Cookies sub-tabs

Requested directly: separate headers, body, and cookies into their own sub-tabs, for both
Request and Response. Previously both were one long block of mixed text - a response's
`Set-Cookie` lines sitting right in the middle of its other headers, a request's `Cookie`
header buried the same way among everything else.

Both panels now have three sub-tabs - **Headers**, **Body**, **Cookies** - built from a
new shared helper (`splitCookiesFromHeaders()`) that pulls cookie lines out of the raw
headers text: a response's one-or-more `Set-Cookie:` lines go straight to the Cookies
tab, and a request's single `Cookie: a=1; b=2; c=3` line is split on `;` into one row per
cookie, since a long semicolon-joined line is hard to actually scan. The existing
Raw/Pretty/Render HTML toggle for Response Data now lives specifically inside its Body
sub-tab, since those modes apply to the body's own presentation, not to headers or
cookies.

Built as two new shared classes - `RequestViewerPanel` and a restructured
`ResponseViewerPanel` - reused identically by both the Live Dashboard's and the Failures
tab's own Request/Response panels, the same sharing pattern this plugin's other viewer
components already use. One honest limitation worth noting: the Failures tab's underlying
data model never captured request/response headers at all (a pre-existing gap, not
something this introduced), so its own Headers and Cookies sub-tabs correctly show "none
captured" rather than fabricating data that was never actually there.

## Sampler Result: a compact table instead of loose text, and a smaller font

Requested directly, alongside the above. The Sampler Result tab (and the Failures tab's
equivalent "General" tab) previously showed a loose stack of `Label: value` lines at the
shared document's normal body text size. Both now render as a properly aligned two-column
table at its own smaller, dedicated font size, via a new shared `buildKeyValueTable()`
helper so both panels stay visually consistent with each other rather than drifting
independently.

## Tools menu entry removed; toolbar icon is now the only way in

Reported directly from a screenshot of a crowded Tools menu: the
**SmartBlitz - Add Insight Dashboard to Selected Node** entry was pure
redundancy, since the toolbar icon already does the exact same thing
in one click. Removed the menu item; the toolbar icon is unchanged and
still the way to add this plugin to the currently-selected tree node.

## Removed: Import Resource Metrics (no purpose, per direct feedback)

Pulled out entirely - not just hidden from the "Run Tools" menu, which
would have left the underlying resource-metrics charts, import dialog,
and data-feeding code sitting around unreachable. Removed: the menu
entry and its dialog (`ResourceMetricsImportDialog.java`, deleted),
the dedicated chart row below the main four charts, the fields and
color constant backing it, and the per-refresh code that fed it live
data.

## Live Dashboard: detail panel was opening at an unreadable width

Reported directly from a screenshot: the "Selected row" panel on the
right opened so narrow that its tabs wrapped onto separate lines and
its own text clipped. The cause is a well-known `JSplitPane` gotcha:
`setResizeWeight()` only governs how *extra* space gets distributed on
a later resize - it does nothing for the split's *initial* position,
which without an explicit `setDividerLocation()` call often defaults
to the left component's own preferred width instead. For a wide table
with many columns, that squeezed the detail panel into a sliver on
every first open. Fixed with an explicit initial divider location
matching the existing resize weight (0.68), deferred one tick via
`invokeLater()` since setting it before the component has its actual
on-screen size is a separate, equally well-known case where it's
silently ignored.

Also shortened the "Per-request stats" panel's border title - it was a
full sentence of usage instructions where a border title is normally
just a label - moving the how-to-use part into a tooltip on the same
panel instead, available on hover rather than permanently taking up a
line of text.
<!-- Add a JMeter-Plugins.org badge here once listed: https://jmeter-plugins.org/?search=custom-live-dashboard -->

## Requirements

- Apache JMeter 5.6.3 (built/tested against this version; other 5.6.x
  versions are likely compatible but untested)
- Java 11+
- (Optional, for the AI tabs only) [Gemini CLI](https://github.com/google-gemini/gemini-cli)
  installed and logged in locally — everything else works without it

## Installation

**Via JMeter Plugins Manager** (once this plugin is listed there): Options
→ Plugins Manager → Available Plugins → search "Insight Dashboard" →
check it → Apply Changes and Restart JMeter.

**Manual install:** download `jmeter-insightdashboard.jar` from this
project's [Releases](../../releases) page (or build it yourself with
`mvn clean package`) and drop it into
`<your-jmeter-install>/lib/ext/`, then restart JMeter. Add it to your
test plan via **Add → Listener → SmartBlitz - Insight Dashboard (AI)**.

## In-GUI Help tab

A third tab, **Help**, sits alongside Live Dashboard and Results Summary
(AI) in the Insight Dashboard listener. It's the same content as
`VIEWING-RESULTS-GUIDE.md` in this project, rendered as theme-aware HTML
directly inside the listener \u2014 no need to go find a separate file.
Covers:
- Viewing results after a headless (`-n`) run, including the exact
  command for XML output format (needed for the transaction/sub-request
  tree to reconstruct correctly on reload) and the Browse-to-load steps.
- Live view with remote Linux load generators via JMeter's built-in
  distributed/remote testing mode (`remote_hosts`, the RMI keystore
  requirement since JMeter 4.0, `Run → Remote Start All`, and the
  common firewall/version-mismatch gotchas).

## Build

Requires Maven and JDK 11+. Adjust `<jmeter.version>` in `pom.xml` to match
the JMeter version you're deploying into (defaults to 5.6.3).

```bash
mvn clean package
```

This produces `target/jmeter-insightdashboard.jar`.

## Install

Copy the jar into your JMeter install's `lib/ext/` folder:

```bash
cp target/jmeter-insightdashboard.jar <JMETER_HOME>/lib/ext/
```

Restart JMeter (or your CI runner's JMeter process). JMeter scans `lib/ext`
on startup and auto-discovers GUI components found there — no registration
file needed. Add it to your test plan via **Add → Listener → Live
Dashboard**.

Then run as usual:

```bash
jmeter -n -t test.jmx -l results.jtl
```

`report.html` is written to the path you configured (relative paths resolve
against JMeter's working directory) once the run completes — handy to wire
into a Jenkins/GitLab CI post-build step that publishes the HTML artifact.

## Load JTL File…

A button on the Live Dashboard tab loads any saved `.jtl` file from
disk, replacing whatever's currently shown — not tied to the Remote
Run tab or SSH at all, just a plain local file. Confirms before
clearing the current dashboard, since loading replaces it entirely.

**Whether this shows a real tree depends entirely on the file, not on
this button.** It sniffs the file's actual first character rather than
trusting the `.jtl` extension (which is used for both formats, so the
extension alone says nothing) — an XML-format file (saved with
"Generate parent sample" enabled during the original run) genuinely
contains the nesting data and loads through JMeter's own
`SaveService.loadTestResults()`, preserving it correctly. A CSV-format
file (the far more common default) has nowhere in its structure to put
a parent/child relationship at all, so it always loads as a flat list
— not a limitation of this feature, just what the format can hold.
After a load, a message states plainly which happened, so there's
never ambiguity about whether a flat result means something went
wrong or just reflects the file's own format.

## Live in-GUI dashboard (like NeoLoad's live view)

A second listener, **`CustomDashboardVisualizer`**, is included alongside
the HTML report listener. It shows up as `Add > Listener > Custom Live
Dashboard` and renders, while the test runs:

- Stat cards: Total Samples, Errors, Error Rate, Avg Response Time, Throughput
- Two rolling line charts: average response time and throughput, updated once a second
- A live per-label table (samples, errors, error %, avg/min/max)

It reuses JMeter's own `AbstractVisualizer` base class, which is where
Clear / Clear All come from (via the separate `Clearable` interface,
unrelated to anything below) — no extra code needed for those. The
standard "Write results to file / Read from file" section is hidden:
confirmed from `AbstractVisualizer`'s own real source that this panel
is a single, cached field embedded directly inside `makeTitlePanel()`'s
own returned panel, not something added via a separate, skippable
call — so it's suppressed by hiding that specific, shared component
instance (`getFilePanel().setVisible(false)`) rather than by leaving
out a call that was never actually optional. Every sample is still
processed directly in memory either way; this section never did
anything for this plugin.

**This one is GUI-mode only by design.** A live dashboard has nothing to
render to in headless (`-n`) mode — see the **Help** tab (or "In-GUI
Help tab" below) for how to view results after a headless run, or live
while watching remote load generators.

**Filtering, sorting, and transaction/sub-request tree view:**

- **Filter box** — type part of a transaction name to only show matching rows.
- **Min Avg (ms)** — only show transactions whose average response time is at or above this threshold (leave blank for no filter).
- **Sort by / Asc-Desc** — reorders the transaction (top-level) rows by any column; defaults to Avg (ms) descending, i.e. slowest transactions first.
- **Expand/collapse** — click a transaction row (one with a ▶/▼ marker) to show or hide its sub-requests, indented underneath it with a └ marker.

**Important caveat on the tree view:** JMeter only attaches child
sub-requests to a parent sample when a **Transaction Controller** has
**"Generate parent sample"** checked. With that on, this listener receives
one sample for the whole transaction (its overall time), and that sample
carries its sub-requests, which is what populates the expandable child
rows. Without that setting, JMeter sends each request as its own
independent flat sample — there is nothing to nest, and those rows simply
won't show an expand marker. If you want the tree behavior, wrap the
relevant samplers in a Transaction Controller and enable that checkbox.

## Render HTML showing completely blank for a real page

Reported right after the view-modes feature above shipped: Pretty
showed the login page's markup just fine, but Render HTML was
entirely empty for the exact same response. Checking how the body is
actually captured found the cause: it's truncated at a fixed character
limit with a `"\u2026 [truncated]"` marker appended, and that limit was
only 3000 characters - easily exceeded by a real page's `<head>`
section alone (multiple CSS/JS `<link>` tags, several `<meta>` tags)
before any `<body>` content even begins. The result handed to Render
HTML was a structurally broken, unclosed document - and unlike a real
browser, Swing's own basic HTML renderer isn't forgiving about that:
it can render nothing at all rather than showing whatever partial
content it received.

Fixed two ways. The capture limit is now far more generous (20,000
characters) - the memory cost of this is trivial, since it's one
stored snippet per unique transaction label (overwritten on each new
sample for that label), not one per sample, so even dozens of distinct
labels at the new limit amounts to a few MB at most. And for whatever
still exceeds even that higher limit, Render HTML now detects the
truncation marker, strips it (since the marker text itself isn't valid
HTML and could land inside a tag or attribute), and shows a clear
warning banner at the top of the rendered page instead - so a
genuinely-too-large response explains itself rather than just looking
like the feature is broken.

## Fixed: the toolbar icon was appearing in the wrong place

Reported directly from a screenshot: the icon was landing at the far
right of the toolbar, after JMeter's own status indicators (elapsed
time, warnings, thread count), rather than near the other icons.
`toolbar.add()` appends to the absolute end of the toolbar's children -
past a flexible spacer JMeter uses to push those indicators to the far
right. Fixed by finding the index right after the last button-like
component already present and inserting there instead, landing grouped
with the other icons. Same fix applied across this plugin and its two
siblings.

## Fixed: the toolbar icon wasn't actually appearing

Two classic Swing gotchas, not anything JMeter-specific: `toolbar.add()`
alone has no visible effect on a container that's already visible and
already laid out (the main toolbar is, well before this code runs) -
fixed with an explicit `revalidate()`/`repaint()` right after adding
the button. As a defensive second layer, the whole attempt now runs via
`SwingUtilities.invokeLater(...)` rather than synchronously, in case
`getMenuItemsAtLocation()` is ever called before JMeter's own toolbar
is fully constructed. Same fix applied identically across this plugin
and its two siblings (Correlation Studio / JMeter Workbench and MCP
Bridge), which all used the same pattern and had the same bug.

## A quick-add shortcut \u2014 Tools menu and toolbar icon

Requested as a follow-up to the same shortcut added for this plugin's
siblings, Correlation Studio (now "JMeter Workbench") and MCP Bridge.
Worth being direct about why this one works differently: those two are
self-contained dialogs, opened the same way regardless of what's
selected in the test plan. Insight Dashboard isn't a dialog at all -
it's a *Listener* GUI component, normally added via JMeter's own
right-click **Add \u2192 Listener \u2192 ...** menu. So "quick access" here
means something different: a new **Tools \u2192 SmartBlitz - Add Insight
Dashboard to Selected Node** entry, plus a matching toolbar icon, both
inserting a new Insight Dashboard node directly under whatever's
currently selected in the tree - via `JMeterTreeModel`'s own
documented, public `addComponent()` method, the same API JMeter's own
tree-manipulation code uses, not a workaround. The new node is
selected and scrolled into view immediately, and selecting something
that can't legally hold a Listener (confirmed via the thrown
`IllegalUserActionException`) shows a clear message rather than a raw
stack trace.

One real bug caught before shipping this: building the `TestElement`
this needs means constructing a throwaway `CustomDashboardVisualizer`
instance, but its constructor unconditionally starts a live,
once-a-second refresh `Timer` (needed for the dashboard's actual,
displayed use, but not for this). Discarding that instance without
stopping the timer first would have leaked a forever-ticking timer
into the background every single time this action ran. Fixed with a
dedicated `createStandaloneTestElement()` factory method that builds
the element and stops the timer before handing anything back.

The toolbar icon itself uses the same `GuiPackage.getMainToolbar()`
workaround (and the same honest caveat about it not being an official,
documented JMeter API) as the sibling plugins' own toolbar buttons.

## Response Data: Raw / Pretty / Render HTML view modes

New toggle at the top of both the Live Dashboard's and the Failures
tab's Response Data panels: **Raw**, **Pretty**, and **Render HTML**.

**Pretty** reuses `ResponseFormatter` - already in this codebase and
already doing robust, string-literal-aware JSON/XML re-indentation with
a safe fallback to the original text if anything looks off - but now
called with no Content-Type hint at all, forcing it to try detecting
JSON/XML from the body's own shape even when a response's actual
Content-Type header didn't trigger that automatically on its own.

**Render HTML** is the genuinely new capability: rather than showing a
response's markup as indented text, it switches to Java's own HTML
renderer and actually interprets the body as a page - useful for the
Keycloak login pages and other full HTML responses this project's
sample scripts already produce. Worth being upfront about the one real
limitation: Swing's built-in HTML renderer is basic and old - no
modern CSS, no JavaScript - so a simple page's text and structure will
show up, but a modern, heavily-styled page won't look anything like it
does in a real browser.

Raw stays available as a true, unmodified view for exactly what was
received, with no reformatting at all.

## Copying (and exporting) only ever included visible/expanded rows

Reported as copy only grabbing "the ones visible and expanded" rather
than the whole table. Checking the table's underlying model confirmed
why, and that it went deeper than just copying: a transaction's
sub-requests are only added to the table's own row list while that
transaction is expanded in the UI - collapsed children aren't hidden,
they're simply absent from the model entirely. A plain Ctrl+C only
ever sees what's in the model, so it could only ever copy whatever
happened to be currently expanded on screen. Checking **Export CSV**
and **Export Excel** found the exact same bug: both were built on that
same visible-only model, not a copy anyone had noticed because the
missing rows aren't obviously missing in a file you don't inspect row
by row.

Fixed at the root: a new `buildFullRows()` mirrors the table's own
filter/sort logic exactly, but always includes every sub-request
regardless of its parent's current expand state. Export CSV and Export
Excel both now use it, so neither loses collapsed children anymore. A
new **Copy Table** button uses the same data to put every matching
transaction and all its sub-requests on the clipboard as tab-separated
values - ready to paste straight into Excel or Sheets - rather than
relying on the table's own default Ctrl+C, which has no way to see
what isn't in its model to begin with.

## Collapsing charts no longer reclaimed the space \u2014 a direct side effect of the scroll-pane fix

A regression from wrapping the chart grid in its own `JScrollPane`
earlier this session (to fix charts' bottom axis labels getting cut off
on a short window): the "Show/Hide Charts" toggle was still only
calling `charts.setVisible(...)` on the grid itself, not on
`chartsScroll` - the `JScrollPane` wrapper that became the actual
`BorderLayout.CENTER` component once that fix landed. An invisible
chart grid sitting inside a still-visible, now-empty scroll pane still
occupies that scroll pane's own space, which is exactly the large blank
gap reported after collapsing.

Fixed by toggling `chartsScroll` itself instead of the grid nested
inside it - now correctly collapses the whole area to just the header's
height, same as it did before the scroll-pane wrapper was introduced.

## The new branding bar never actually appeared \u2014 an old one was silently winning

Reported as "I don't see anything at bottom," and the screenshot
confirmed why: a small, pre-existing text-only footer (apparently added
in an earlier session, serving the same basic purpose - plugin name,
version, a third-party-plugin note) was already occupying
`BorderLayout.SOUTH` on the main panel. My new `PluginBranding.buildBrandBar()`
call targeted that exact same position - and since a `BorderLayout` can
only hold one component per position, whichever `add()` call runs last
silently wins. The old footer's call happened to run after mine, so the
new, more prominent bar was built correctly but never actually
rendered at all.

This was a real miss on my part: I didn't search for existing branding
code in this file before adding new code to the same spot. Fixed by
removing the old, now-redundant footer entirely, since the new bar
covers the same purpose (name, version, third-party attribution) with
the more prominent, intentional design actually requested.

## A branding bar, like commercial JMeter plugins show on their own panels

New bar at the very bottom of the plugin's main panel, below the tabs
so it's visible regardless of which one is active - a small icon and a
styled wordmark, in the spirit of how commercial JMeter plugins (e.g.
BlazeMeter's bzm samplers) mark their own configuration panels.

Worth being direct about the one real constraint here: there's no
actual logo image to work with, since this is a custom, in-house
plugin rather than an established product with designed brand assets.
Bundling and loading an external image file as a resource is also
something this environment has no way to actually compile-test.
So the icon is drawn directly in code instead - three bars of
increasing height, a small, literal bar chart echoing the response-
time/throughput/error-rate charts this plugin is actually about, in
the same accent colors already used for those charts elsewhere in this
file. The wordmark reads the version from the jar's own manifest
(already configured via `addDefaultImplementationEntries` in
`pom.xml`, confirmed by checking directly), so it can't drift out of
sync with `pom.xml`'s own version the way a hardcoded string would.

Correlation Studio already had a similar, more subtle text-only
footer (`PluginBranding.buildFooter()`, shown on its five dialogs) -
this is a new, separate, more prominent one for Insight Dashboard's
main panel specifically, in the same spirit but sized for a different
purpose (the one, always-visible main screen, versus a small corner
note on a transient dialog). Happy to add the same prominent treatment
to Correlation Studio too, if wanted.

## Chart bottoms (X-axis) getting cut off on a shorter window

Root cause: the Live Dashboard's charts were never wrapped in anything
that could scroll. Each chart has a preferred height (170px) that
reserves room for its own bottom axis labels, but after everything
added to the controls area this session, a shorter JMeter window no
longer has enough total height to give every section its full
preferred size - and with nothing able to scroll, the layout just
squeezes each chart below the height it needs, cutting off exactly the
bottom row of axis labels reported as missing.

Fixed by wrapping the chart grid (and the resource-metrics chart grid
below it) in their own scroll areas, via a small `ScrollableChartsPanel`
that always matches the available width (so no horizontal scrollbar
ever appears) while staying free to exceed the available height. On a
short window, the charts now keep their full, readable size and the
user scrolls vertically to see all four, rather than every chart being
silently squeezed smaller than what it needs.

## "Create Correlation Rule\u2026" \u2014 automating the handoff to Correlation Studio

New button in the Find Dynamic Values dialog, only enabled for a
"Named field" finding (one with an actual field name to anchor a
reusable regex to - a UUID-shaped or unnamed "possible token" finding
has no such anchor, and a regex built only from one response's literal
text would be overfit to it, not a real correlation rule).

Clicking it opens a small form, pre-filled from the finding: the
producing sampler, the variable name, and a suggested regex built from
the field's own name (e.g. for `session_code`, something like
`(?i)session_code["']?\s*[:=]\s*["']?([A-Za-z0-9\-_.=]+)`) - matching
the same name/value shape the scanner itself looked for, so it
generalizes across different response instances rather than being tied
to this one value. Review and adjust anything before saving; the regex
is validated the same way Correlation Studio's own rule-authoring
dialog validates one (compiles, has a capturing group) before it's
accepted.

**This is a real, working handoff, not just a clipboard copy** - Save
writes the rule directly to
`~/.jmeter-auto-correlator/templates.json`, the exact file Correlation
Studio's own `TemplateStore` reads from and writes to, in the exact
JSON shape its `CorrelationTemplate` class already defines. The rule
shows up in Correlation Studio's own template library the next time
that dialog is opened or refreshed - no manual re-entry needed.

Worth being precise about why this is safe, after being deliberately
cautious about deeper integration earlier in this project: this isn't
a code-level dependency on Correlation Studio, and it doesn't call into
any of its classes. It writes a plain JSON file in a format already
fully defined and documented in Correlation Studio's own source -
confirmed, not assumed, by reading `CorrelationTemplate.java` and
`TemplateStore.java` directly, and by diffing the two plugins' own
`MiniJson.java` files (byte-for-byte identical aside from the package
declaration, so anything written by one is guaranteed readable by the
other). If Correlation Studio isn't even installed, this still works
harmlessly: it just writes a JSON file that nothing reads yet.

## Sampler Result tab's font size reduced to match the rest of the app

The Sampler Result tab (and its Failures-tab equivalent) stayed HTML-
based, since its bold labels and colored status text are worth keeping
- but its body text was 13px and its heading 14px, visibly larger than
the 12px now used for the plain-text Request/Response panels next to
it. Reduced both (and h1/h2 proportionally) to 12px/13px for
consistency across the whole detail panel. Left the separate stylesheet
used for the exported, standalone HTML report untouched - that one
renders a printable document viewed in an actual browser, where the
original, slightly larger sizing is appropriate and was never part of
this complaint.

## Request/Response Data panels switched to plain text \u2014 HTML wrapping never actually worked

Reported a third time: even after the CSS fix and the viewport-width-
tracking fix, long lines (a full URL, a session cookie) still ran off
the edge with no wrapping. Two HTML/CSS-based attempts failing in a row
pointed at the real issue: Swing's `HTMLEditorKit` is a quite old,
limited HTML renderer, and its support for `white-space: pre-wrap` on
a `<pre>` element appears to simply not be honored, regardless of what
CSS is set - no amount of further CSS tuning was going to fix that.

The Request and Response Data panels - both in the Live Dashboard and
the Failures tab - are now plain `JTextArea`, not HTML at all.
`setLineWrap(true)` with `setWrapStyleWord(false)` gives simple,
well-tested wrapping that doesn't depend on any HTML rendering
whatsoever - and wraps even mid-token when there's nowhere earlier to
break, which is exactly what a long, spaceless cookie value needs. An
explicit monospace font and theme-matched colors replace what the CSS
used to provide. Section headers (previously `<h4>`) are now plain
dashed-underline text, since `JTextArea` has no bold/heading styling at
all - a small readability trade-off for content that now, finally,
actually wraps.

The Sampler Result, Extracted Variables, and every other HTML-based
pane in this plugin (AI panes, Help, Anomalies log) are unaffected and
keep their richer formatting - only the two panes carrying raw request/
response text, where that formatting was never essential in the first
place, changed.

## Request/Response/Sampler Result panels still not actually wrapping

Reported after the earlier CSS fix: long lines (a full request URL, a
session cookie in a response header) were still running off the right
edge rather than wrapping, and the font still didn't match View
Results Tree's.

The CSS fix from before (`white-space: pre-wrap`, an explicit smaller
monospace `font-size`) was real and necessary, but not sufficient on
its own - it was missing a second, well-known Swing behavior:
`JEditorPane` calculates its own "natural" width from its HTML content,
and `white-space: pre-wrap` only controls where *within* that
(potentially very wide) natural width a line wraps - not the width
itself. Without an explicit override, the pane is free to grow wider
than the visible viewport, so the content technically "wraps" but at a
width nobody can see without scrolling - which a plain `JScrollPane`
handles by showing a horizontal scrollbar instead.

Fixed with a small `WrappingEditorPane` subclass (overriding
`getScrollableTracksViewportWidth()` to always return `true`), now used
for every `JEditorPane` in this file - the Live Dashboard's Request/
Response/Sampler Result/Extracted Variables tabs, the Failures tab's
equivalents, the AI panes, and the Help tab. Combined with the earlier
CSS, content now genuinely wraps to whatever width is actually visible,
matching how View Results Tree behaves.

## Columns jumping/swapping instead of resizing \u2014 a direct consequence of the previous fix

Reported immediately after the custom resize mechanism above shipped:
dragging a column boundary made columns swap places with their
neighbors instead of the dragged one actually getting wider.

The cause was specific and direct: `setResizingAllowed(false)` disables
only *resizing* - but `JTableHeader` has a second, separate, enabled-
by-default feature, drag-to-*reorder* columns, that was never touched.
With resizing off, the native UI delegate started treating every header
drag as a reorder attempt instead - which is exactly what "columns
swapping with their neighbors" is, as opposed to the dragged column
getting wider. The custom resize logic from the previous fix was
running correctly the whole time, just alongside this now-active
native reordering behavior firing on the very same drag gesture.

Fixed by also calling `setReorderingAllowed(false)` - nothing in this
table ever intentionally used column reordering, so disabling it
removes the native mechanism from header drags entirely, leaving only
the custom resize handling in control.

## Column resize replaced with a fully custom mechanism; response panel font/wrapping fixed

**Column resize still wasn't draggable** even after the earlier cursor
fix and the double-click-to-auto-fit addition. With no way to
interactively debug `JTableHeader`'s own internal drag-handling logic
in this environment, continuing to patch around whatever was actually
preventing it from working wasn't a reliable path forward. Replaced it
entirely: `header.setResizingAllowed(false)` turns off the native
mechanism outright (so there's no risk of it fighting a second one over
the same gesture), and a fully custom
`mousePressed`/`mouseDragged`/`mouseReleased` sequence now handles the
drag directly — tracking which column a press started near a boundary
on, and computing the new width straight from how far the mouse has
moved on each subsequent drag event, with no dependency on the native
implementation at all. Double-click-to-auto-fit and click-to-sort both
continue working exactly as before, unaffected by this change.

**The Request/Response Data panels' font didn't match the rest of the
app, and long values (a session cookie, a token) ran off the right
edge with no good way to see the rest.** Both traced to the same CSS
rule: the `<pre>` style used for this content had no explicit
`font-size`/`font-family` at all (falling back to the HTML renderer's
own, visibly larger default monospace), and used `overflow-x: auto`,
which is exactly what forces horizontal scrolling instead of wrapping.
Fixed by setting an explicit, properly-sized monospace font matching
the rest of the UI, and switching to `white-space: pre-wrap` with
`word-break: break-all` — the latter specifically because a long,
spaceless value like a cookie has no natural wrap point at all, so
`pre-wrap` alone wouldn't have actually wrapped it. Applied to both the
in-app view and the exported HTML report.

## Double-click a column boundary to auto-fit it

Dragging a column boundary to resize it was fixed earlier this
session, but JTable's own native grab zone for that is genuinely
narrow by default - a few pixels at most - which makes it finicky to
hit precisely even when the underlying mechanics work correctly.
Rather than rely on drag precision alone, added the standard
**double-click a column boundary to auto-fit** convention used by
Excel and most other table/grid UIs: double-clicking the Label column's
right edge resizes it to fit its longest currently-visible text (tree
arrow/indent included, not just the raw label), with a sensible floor
and ceiling so neither a short table nor one unusually long label
produces an unreasonable width. The boundary-detection margin used for
cursor feedback was also widened slightly for a more forgiving target.
Both are now mentioned directly in the Label column header's own
tooltip, since neither was otherwise discoverable.

## Popup dialogs appearing in the wrong place, and the controls area still too tall

Two follow-ups to the reorganization above.

**Message dialogs (e.g. "No transaction timing captured yet") were
appearing positioned far from the main window** rather than centered
on it. All 19 `JOptionPane` calls across this file were passing `this`
(the Visualizer's own `JPanel`) as the parent component directly.
`JOptionPane`'s own internal logic for resolving "which window to
center on" from an arbitrary component can behave unreliably when that
component sits deep inside a complex, non-standard GUI hierarchy - and
this panel is embedded inside JMeter's own GUI tree, not a simple
top-level window. Fixed by explicitly resolving the actual Frame via
`SwingUtilities.getWindowAncestor(this)` first (the same approach
already used successfully for every custom dialog added this session)
and passing that instead, for all 19 call sites.

**The reorganized controls area was still too tall**, even after being
grouped — five-plus rows of configuration (Filter & Sort, Alerts, View
& Tools, Saved Views, the capture-detail checkbox) stacked vertically
left little room for the actual table underneath, which is the entire
point of this screen. Restructured so only **Filter & Sort** — the
controls actually touched on every refresh — stays always visible.
Everything else (Alerts, View & Tools, Saved Views, capture-detail)
now collapses behind a single **"\u25b6 More Options"** toggle, collapsed
by default, the same one-click pattern already used for the charts
section. The always-visible controls area drops from five-plus rows to
two, with the full configuration still one click away whenever it's
actually needed.

## Controls bar reorganized \u2014 18 controls in one undifferentiated row, grouped into three

Reported: "Columns" and "Errors only" were genuinely hard to find, and
the area felt cramped when charts were collapsed. Looking at the actual
code confirmed why: across many turns this session, every new filter,
threshold, checkbox, and button got added to the same single
`FlowLayout` row — 18 distinct controls by the end, with zero grouping
or visual separation. At any realistic window width that wraps to
several rows that all look identical, so a specific control is easy to
lose track of.

Reorganized into three clearly labeled, separated groups:
- **Filter & Sort** \u2014 name filter, thread group, min avg, **Errors
  only** (moved here, next to the other filters, where it belongs),
  sort, clear filters
- **Alerts** \u2014 SLA/error-rate thresholds and the three notification
  opt-ins
- **View & Tools** \u2014 **Columns**, CSV/Excel export, and a new **Run
  Tools \u25bc** dropdown consolidating the four occasional-use buttons
  (Save Run Snapshot, Compare Runs, Transaction Timeline, Import
  Resource Metrics) that were previously spread across individual
  buttons taking up as much space as the everyday filter controls

A thin separator sits between each group, and between the controls bar,
the Saved Views (preset) row, and the capture-detail checkbox below it.

Also fixed the cramped collapsed-charts spacing directly: the charts
header row (Show/Hide Charts, Viewing: context, Show Overall, Load JTL
File) now carries its own bottom padding, so it has breathing room
below it whether the chart grid itself is shown or hidden, rather than
relying solely on the outer layout's gap.

**What "Saved Views" (the preset row) actually does**, now spelled out
directly in its own tooltip rather than only in a one-line label: it
remembers every filter field and checkbox in the controls bar above,
plus which table columns are visible, under a name you choose. Pick a
saved name from the dropdown to apply that whole combination
immediately; "Save Preset\u2026" captures the controls' current state
under a new (or existing) name; "Delete Preset" removes one you no
longer need. The point is switching between a few recurring views \u2014
"errors only + key columns" vs. "full detail," say \u2014 without
resetting every control by hand each time.

## Two more real compile errors, both from an actual build

Reported via Maven again, and both are exactly the kind of mistake that
brace/paren balance, unused-import, and symbol-reference-count checks
can't catch — not because those checks are wrong, but because neither
error affects brace balance or symbol *existence* at all:

1. **`incompatible types: <anonymous MouseAdapter> cannot be converted
   to Component`.** A lambda handing off to the new "Find Dynamic
   Values" dialog was nested inside an existing anonymous
   `MouseAdapter` subclass's own method body — so its bare `this`
   resolved to that `MouseAdapter` instance, not
   `CustomDashboardVisualizer`, which isn't a `Component` at all.
   Fixed with the explicit `CustomDashboardVisualizer.this` qualified-
   this syntax. Audited every other `getWindowAncestor(this)` call site
   added this session for the same risk — the rest are all direct
   lambdas on button listeners called from a top-level method, where
   bare `this` is correct.
2. **`cannot find symbol: method isNearColumnBoundary(...)`.** This
   method ended up defined *inside* the unrelated `LabelTableModel`
   nested class instead of directly on `CustomDashboardVisualizer`,
   where its one and only caller (`configureTable()`) actually lives —
   an insertion-point mistake, not a logic error in the method itself.
   Moved it to sit directly after `configureTable()`. Checked every
   other method added this session by its indentation level for the
   same kind of misplacement; all the rest were already correctly
   positioned.

## Build fix: a real compile error from an undeclared dependency

Reported via an actual Maven build: `package
org.apache.jmeter.protocol.http.sampler does not exist`. The HTTP-
method lookup added for the detail panel's General tab used
`HTTPSampleResult` directly — but that class lives in
`ApacheJMeter_http`, a separate Maven artifact this project's `pom.xml`
never declared as a dependency (it only declares `ApacheJMeter_core`
and `ApacheJMeter_components`). This is exactly the kind of mistake the
brace/paren-balance and unused-import checks used throughout this
project can't catch, since there's no real Java compiler available in
this environment — only an actual build against the real dependency
set surfaces it.

Fixed by removing the dependency entirely rather than adding
`ApacheJMeter_http` (untestable here, with its own risk of a wrong
version or scope): the HTTP method is now derived directly from
`getSamplerData()`'s own first line (`"METHOD URL"`, standard for any
HTTP sample), which needs nothing beyond the base `SampleResult` class
already used everywhere else in this file. Also audited every other
`org.apache.jmeter.*` import across the whole project against this
same risk — all the rest are core JMeter classes (`SampleResult`,
thread management, save/reporting utilities, the base GUI framework),
not protocol-specific ones, so none of them carry this same problem.

## Proactive anomaly flagging \u2014 surfaced during the run, not just on request

Two new opt-in checkboxes: **Flag anomalies automatically** and **Ask
AI to explain anomalies**, plus a new **Anomalies** tab.

The detection itself is purely local and statistical, with no AI
involved at that stage at all: each transaction's last ~10 seconds is
compared against its own running average *for this test* (not a
threshold you configure — that's "Notify on SLA/error breach" on the
same tab) once it has at least 10 samples to form a meaningful
baseline. A recent average more than 50% above its own running average,
or a recent error rate more than 15 points above its own running rate,
flags it — once per transaction per run, via a desktop notification and
a new entry in the Anomalies tab's log.

**Ask AI to explain anomalies** is a separate opt-in on top of that:
only when an anomaly is actually flagged does it fire a single
background AI call (reusing the same command configured on the Results
Summary (AI) tab) for a short, 2-4 sentence explanation, appended to
that log entry once it responds. This two-stage design — cheap,
local detection running on every refresh tick, with the AI called at
most once per flagged anomaly rather than continuously — was deliberate
from the start: calling an AI CLI every few seconds during a live test
would be slow, could cost real money depending on the underlying
service, and risks piling up overlapping calls if a response takes a
while. Off by default, since both the detection and especially the AI
explanation are real costs worth opting into rather than paying always.

## System resource overlay \u2014 "response time spiked" vs. "...and so did server CPU"

New **Import Resource Metrics…** button. Imports a CSV of externally-
collected system metrics (CPU, memory, anything numeric) — a header
row, then `timestamp,metric1,metric2,…` — and plots each selected
column as its own chart, placed right below the existing response-
time/throughput/error-rate charts, aligned on the exact same absolute-
time window. Turns "response time spiked at 14:32" into "...and so did
server CPU" — the actual diagnostic question this is for.

The timestamp column (assumed to be the first one) auto-detects epoch
seconds, epoch milliseconds, or a handful of common date-time formats.
Any second with no exact sample in the imported data holds its last
known value rather than showing a gap, since metrics like this are
typically sampled every few seconds, not every single second.

Worth being upfront about the scope here: this is CSV import, not a
live HTTP metrics puller. A generic HTTP endpoint could have any shape,
auth scheme, and polling contract — building a one-size-fits-all live
integration without a real endpoint to test it against would be
guessing at an integration rather than building a working one. CSV
import is also exactly what the common existing workflow already
produces: JMeter's own PerfMon plugin, or any simple monitoring script,
writing metrics to a file during the run. Imported metrics are cleared
on **Clear Data** along with everything else, since they're tied to a
specific run's absolute timestamps and would otherwise show stale,
misaligned data against a new one.

## Find Dynamic Values \u2014 a lighter link to Correlation Studio

New **Find Dynamic Values…** button, in both the Live Dashboard's detail
panel and the Failures tab's right-click menu. Scans the sample's
response body and headers for values that commonly need correlating in
a load-test script — known token-like field names (session, csrf,
token, state, client_id, and others), UUID-shaped strings, and long,
random-looking strings as a lower-confidence fallback — and lists them
with where each was found, ready to copy individually.

Worth being direct about the design here rather than overselling it:
this is **not** a code-level integration with Correlation Studio. The
two are separately-packaged, independently-installable plugins in
different Java packages — a hard dependency between them, or
reflectively invoking Correlation Studio's own dialogs (which expect
HAR/JTL-recording-shaped data a live JMeter test execution doesn't
produce in the same form), would be fragile to ship without a way to
actually test it against both JARs installed together. Instead, this
scan is fully self-contained within Insight Dashboard, built in the
same spirit as Correlation Studio's own heuristics but with its own,
independent logic. The actual "link" between the two tools is the
**Copy Response Text…** button, which puts the full request/response
text on the clipboard — paste that into Correlation Studio's existing
"Find Value" tool (built earlier this project) if that's also
installed, to trace exactly where a specific value came from and where
it's reused across the whole recording, which is a strictly bigger
question than this single-response scan can answer on its own.

One real limitation: the Failures tab entry point only scans the
response body, not headers, since `FailureDetail` (the older of the
two capture mechanisms here) never captured headers at all. The Live
Dashboard's own detail panel entry point scans both, since the newer
`SampleDetail` behind it does capture them.

## Transaction timeline (Gantt view) \u2014 where did the time in one iteration go

New **Transaction Timeline…** button. Picks one JMeter thread and shows
its most recent iteration as a sequential, Gantt-style chart — each
named transaction (a Transaction Controller's own parent sample, or a
top-level request not under one) as a horizontal bar from its own start
to its own end, in the order it actually ran, with its duration printed
on or next to the bar and colored red if it failed. Makes "where did
the 45 seconds in one pass through the script actually go" visible at
a glance, instead of scanning a flat, aggregated per-request table to
piece it together.

Deliberately per-thread, not an aggregate across every thread: separate
threads can be at different points in their own loop or interleaved
with each other, so overlaying all of them would produce a chart that
doesn't correspond to any single, coherent pass through the script —
one thread's own sequence is what's actually meaningful here.

"Most recent iteration" is detected heuristically: a transaction name
reappearing for a thread (the normal case for a script that loops
through the same named transactions repeatedly) is treated as the start
of a new iteration, and the timeline resets to track that new one. A
script whose transaction names never repeat at all wouldn't trigger
that reset, so the view is capped at 60 entries per thread regardless,
as a safety net.

## Run baseline/comparison with a pass/fail verdict

The single most commonly requested feature in load-testing tools, and
something this dashboard had no way to do at all before this: every
run was shown in isolation, with nothing to compare it against.

**Save Run Snapshot…** (new button, top controls bar) captures the
current run's per-transaction stats — samples, errors, avg, P90/P95/P99
— under a name you give it, persisted to a small JSON file under
`~/.jmeter-insightdashboard/runs/` so it survives a JMeter restart.

**Compare Runs…** opens a dialog to pick a **Baseline** and a
**Compare against** run — either one can be a saved snapshot or the
dashboard's own current live/just-ended data — and shows every
transaction side by side with:
- Avg and P95 for both, and the P95 % delta
- Error rates for both
- A **Status** per transaction: `REGRESSION` (P95 or avg crossed a
  configurable threshold, default 20%, or the error rate got worse),
  `IMPROVED`, `OK`, `NEW` (not in the baseline), or `REMOVED` (was in
  the baseline but missing now)
- An overall **pass/fail verdict** at the top — "FAIL — 2 regressions,
  1 new error, 3 improved" or "PASS — 0 regressions, …" — the
  regression threshold is adjustable right there, and recomputes
  immediately

A **Delete Baseline Run…** button removes a saved snapshot you no
longer need (there was previously no way to clean these up at all,
which I caught and fixed while wiring this in), and **Export
Comparison…** saves the full table plus verdict as a plain-text file.

## Column resize, panel collapse visibility, request/response headers, extracted variables, desktop notifications, bug-report export, and saved view presets

A batch of robustness fixes and new features, in priority order from
actively-frustrating bugs to new capabilities:

**Column widths weren't actually draggable.** A custom mouse-motion
handler on the table header (added for the Label-column sort-by-click
feature) was unconditionally forcing the cursor on every mouse move,
fighting with and overwriting the header's own native resize cursor
right when hovering near a column boundary. The underlying resize
mechanics likely still worked, but with no cursor feedback there was no
way to actually find the few-pixel-wide boundary to grab. Fixed by
deferring to the header's own cursor logic whenever the mouse is near a
column edge.

**The side panel's collapse control was too easy to miss.** The toggle
button added previously was buried in a shared row with an unrelated
checkbox. It's now on the detail panel's own header, in bold, directly
associated with what it controls — and the split divider itself is
wider (14px vs. the ~8px default) so the small built-in arrow is
actually clickable too.

**Request/response headers, HTTP method, and protocol weren't shown at
all** in the per-row detail panel — only the body and a few response
fields were. Now captured and shown in their respective tabs, alongside
a new **Extracted Variables** tab: for the selected sample, it shows
every JMeter variable that appears new or changed since that thread's
previous sample — an approximation of what that sample's own
Regex/JSON/XPath extractors just set (JMeter doesn't expose a direct
extractor-to-variable link, so this is a diff against the previous
sample on the same thread, not a precise attribution).

**Desktop notification on SLA or error-rate breach.** New opt-in
checkbox and a new error-rate % threshold field (independent of the
existing SLA/P95 one). Fires once per transaction per run via the
system tray, not repeatedly for an ongoing breach, so a long test
doesn't need to stay in focus to be noticed. Silently does nothing on a
platform without tray support, rather than failing the refresh over it.

**Export a failure as a bug report.** Right-click a failure in the
Failures tab → "Export as Bug Report" copies a plain-text, Jira-
pasteable bundle to the clipboard: the same request/response detail
already captured, plus the AI's analysis from "Explain Failure" —
but only when that analysis actually corresponds to the selected
failure, not a stale answer left over from a previously-explained one.

**Saved filter/column view presets.** Every filter field (text filter,
min avg, SLA, error-rate threshold, errors-only, thread group, sort)
plus which columns are visible can now be saved under a name and
switched back to later, instead of resetting everything by hand each
time. Persisted to a small JSON file in the user's home directory, so
presets survive a JMeter restart.

## Full label on hover, a more discoverable collapse, and an errors-only filter

Three follow-ups to the status dot / detail panel above:

**Full label text on hover.** The Label column truncates long paths with
"…" — hovering any row now shows the complete, untruncated label in a
tooltip, word-wrapped rather than one long line.

**A labeled toggle button for the detail panel**, not just
`JSplitPane`'s own built-in one-touch-expand arrow — that arrow is
still there and still works, but it's small and easy to miss, especially
against a dark theme. A new **"◀ Show Detail Panel" / "▶ Hide Detail
Panel"** button sits next to the capture checkbox and does the same
thing in one click; its label stays correct no matter which of the
three ways (this button, the built-in arrow, or dragging the divider
by hand) was actually used to move it.

**Errors only** checkbox, next to the other filters. Shows just the
rows with at least one error. A transaction row stays visible even
when its own direct error count is 0, as long as any of its sub-requests
failed — so you can still expand into it and find the failing one;
once expanded, only the failing sub-requests are shown, not every
sibling that passed.

## Per-row status dot and request/response detail (View Results Tree-like)

Requested: a visual pass/fail indicator per row in the Live Dashboard's
table, and a way to click a row and see its request/response detail —
the same kind of thing View Results Tree already offers, inside this
dashboard instead.

**Status dot:** every row's Label column now shows a small colored dot
— green once that row has at least one sample and none of them failed,
red if any have. No dot yet for a row with no samples. This reads the
same `Errors` count already shown in its own column; it's not new data,
just a faster-to-scan visual on top of it.

**Request/response detail panel:** a collapsible panel to the right of
the table (drag the divider, or use the small arrow at its edge to
collapse/expand it in one click) with three tabs — Sampler Result,
Request, Response Data — showing whatever was most recently captured
for the currently-selected row.

This is opt-in via a new checkbox above the table, **off by default**:
"Capture request/response detail for every sample." The existing
Failures (AI) tab already captures full detail for every *failed*
sample regardless of this setting, since that's a bounded ring buffer
(up to 30) that was always affordable. This new feature is different:
to show detail for ANY row — most of which are successful — it has to
read every sample's response body as text, not just failed ones. That
per-sample cost is real on a high-throughput load test, which is why
it stays off until asked for. Turning it on doesn't accumulate data
without bound, though: only the single most recent sample is kept per
label, overwriting the previous one, so memory stays bounded by how
many distinct sampler names exist in the script — not by how long the
test runs or how many total samples it produces.

One limitation worth knowing: for a sampler that runs many times (a
loop, multiple iterations), the panel only ever shows the latest
occurrence, not a history of all of them — View Results Tree remains
the tool for that when every single sample matters, not just the most
recent one.

## Selection kept clearing itself

Root cause: the table refreshes every second, and that refresh calls
`fireTableDataChanged()` on the model — which is exactly the signal that
tells `JTable` "the data changed structurally," and `JTable`'s built-in
reaction to that is to clear whatever row was selected. So every refresh
tick was wiping the selection and snapping the charts back to "Overall."

Fixed by remembering which label was selected before each rebuild,
suppressing the selection listener while the table model rebuilds (so that
internal clear-then-restore doesn't get mistaken for you clicking away),
and re-selecting the same row by label afterward. If the previously
selected row no longer exists post-rebuild (e.g. it got filtered out by
the Filter/Min Avg boxes), it correctly falls back to "Overall" instead of
pointing at nothing.

## Code breakdown labels were still too narrow

Increased the label column from a flat one-third of the panel width
(capped at 280px) to 45% of the panel width (capped at 440px), since the
bar itself doesn't need much width to read clearly — this leaves much more
room before truncating, especially for the "Non HTTP response code: ..."
style messages.

**Windows note:** most npm-installed CLIs (Gemini CLI included) are a
`gemini.cmd` shim on Windows, not a raw `.exe`. Typing `gemini` in
PowerShell/cmd resolves that automatically; Java's `ProcessBuilder` calls
the Windows `CreateProcess` API directly and does **not** do that
resolution, which is exactly what produces `CreateProcess error=2, The
system cannot find the file specified` even though the identical command
works fine typed into a terminal. Fixed by routing the command through
`cmd.exe /c` on Windows (detected via `os.name`), so cmd does that
resolution for us, the same as it does interactively. No change needed on
macOS/Linux, where this was never an issue.

**"Not running in a trusted directory" error:** Gemini CLI refuses
headless runs in a directory it hasn't been told to trust — a safety
guard against a hostile file in an unfamiliar folder tricking it into
taking actions there. JMeter runs this from its own `bin` folder, which
has never been trusted interactively, so this fires the first time. The
default command (`gemini --skip-trust -p`) already includes the fix; if
you'd changed the field and removed the flag, put it back. This is safe
for what this tab actually does — it only ever sends plain analysis text
and reads back plain text, never asking Gemini to read/write files or run
tools in that directory. If you'd rather not bypass that prompt at all,
the alternatives are running `gemini` interactively once from JMeter's
`bin` folder to trust it there permanently, or setting
`GEMINI_CLI_TRUST_WORKSPACE=true` in the environment JMeter itself is
launched from.

## Results Summary (AI) tab

A second tab, next to Live Dashboard, laid out as a proper numbered
report rather than a loose dump of stats:

1. **Test Overview** — overall totals (samples, errors, avg response
   time, throughput, SLA threshold if set)
2. **Performance Metrics** — top-10 slowest transactions, top-10 slowest
   requests
3. **Failures & Errors** — failed transactions/requests, top error/response
   codes
4. **SLA Compliance** — breaches against your configured P95 threshold
   (only shown at all if you've set one)
5. **Observations & Analysis** — the one AI-enhanced section, leveraging
   a locally-installed **Gemini CLI** to add a written verdict, key
   findings, likely causes, and recommendations on top of sections 1–4

**Sections 1–4 populate automatically as soon as a test finishes** — no
click needed, no AI involved in any of it, computed directly from the
test data. Section 5 is the exception: it starts as a "not yet generated"
placeholder, and clicking **"Enhance Observations with AI"** is what adds
the written analysis to it. This also means the report is never
"half-built" waiting on a button click — if Gemini CLI isn't set up at
all, you still get a complete, four-fifths-finished report with only the
AI section left as a placeholder, not a blank tab.

**How "test finished" is actually detected:** not solely JMeter's own
`testEnded()` callback — a real test run showed that callback doesn't
reliably reach this component in practice, contrary to an earlier,
untested assumption in this codebase's own comments. There's now a
timer-driven fallback instead: the dashboard already refreshes once a
second regardless of whether `testStarted()`/`testEnded()` ever fire at
all, and that same timer now also watches active thread count (via
`JMeterContextService`, the same source JMeter's own toolbar thread
counter reads) for the precise moment it drops from running to zero.
One known gap: a saved `.jtl` loaded via **Load JTL File…** never
involves any active threads at all, so this specific detection doesn't
apply to that path — only to an actual live run.

**How the AI part works:** no API keys or SDKs are involved. The button
builds a plain-text version of the same report (overall totals, the
per-transaction breakdown exactly as currently shown in the Live
Dashboard table — respecting its filter/sort/expand state — and the top
response/error codes), then runs your Gemini CLI as a subprocess
(`ProcessBuilder`), feeding that report to it over its stdin and reading
its response from stdout. The command itself is an editable dropdown —
pick Gemini or Claude Code from the list, or type any other command
directly (a different path, extra flags, a different CLI entirely); it's
a convenience, not a restriction to only those two options.

**Important:** running the CLI locally does not mean the data stays
local — it still sends the report text to Google's Gemini API over the
network, the same as running it yourself in a terminal. If your
transaction names or test data are sensitive, review the report text
(visible in the tab after generating, since it's exactly what got sent)
before relying on this in a locked-down environment.

**Requirements:**
- Gemini CLI installed and either on your system `PATH`, or give a full
  path in the command field (e.g. `/usr/local/bin/gemini -p`).
- Already logged in / authenticated — run `gemini` once interactively
  first if you haven't, since a headless first-run can hang waiting for
  a login flow this dialog can't complete for you.

**What if Gemini CLI isn't set up in your environment?** The AI layer is
entirely optional — every other Live Dashboard feature (stats, charts,
tree table, SLA highlighting, CSV/Excel export) works completely
independently of it, no setup required. Specifically:

- **Results Summary (AI) tab** — sections 1–4 (test overview, top-10
  slowest, failures, error codes, SLA breaches) populate automatically
  the moment a test finishes, with no AI involved at all. Clicking
  "Enhance Observations with AI" without Gemini set up still works safely:
  the status shows *"AI unavailable (showing data-only report)"* and
  section 5 (Observations & Analysis) just stays at its "not yet
  generated" placeholder, rather than failing or hanging.
- **AI Chat tab** — this one has no data-only equivalent, since a
  conversation genuinely needs something to respond. Sending a message
  without Gemini set up shows an error in the chat transcript itself
  (with whatever your system reported, e.g. "command not found") rather
  than crashing or hanging silently.
- **Failures (AI) tab** — same as Results Summary: the failure list and
  detail view work regardless; only "Explain This Failure" specifically
  needs Gemini.

**Want to use a different AI CLI instead of Gemini?** The command field
is an editable dropdown, not hardcoded to its two preset options — any
CLI that reads additional context from stdin and accepts a prompt-style
flag (similar to Gemini CLI's own `-p`) could work by typing its command
in instead, same as before.

**Claude Code specifically**: its own headless mode (`claude -p`,
documented at
[code.claude.com/docs/en/headless](https://code.claude.com/docs/en/headless))
matches this exactly — reads stdin, prints the result to stdout, exits —
so setting the command field to `claude -p` should work the same way
`gemini -p` does. Confirmed against Claude Code's own official docs that
the interface matches; this hasn't been run against this specific plugin
end-to-end, so treat it as "should work" rather than independently
verified here.

**One caveat worth knowing before switching:** a documented Claude Code
issue reports `claude -p` returning empty output — no error, exit code 0,
just nothing back — on stdin input over roughly 7,000 characters. A test
with many transactions can produce a report that size or larger. Worth a
quick try on a small test first if you switch to it, rather than assuming
it'll scale the same way to a large one.

For any other CLI beyond Gemini or Claude Code, this is genuinely
untested — treat it as a possibility worth trying rather than a
guarantee.

**Design choices worth knowing:**
- **Manual trigger only** — it does not run automatically when a test
  ends. Invoking an external process (and a network call) after every
  run without asking felt like the wrong default; this is easy to add
  later (e.g. an "auto-run on test end" checkbox) if you'd rather have it.
- **90-second timeout** — if Gemini CLI hangs (most commonly because it's
  not authenticated yet and is waiting on a login prompt this dialog
  can't see), the subprocess is forcibly killed and you get a clear error
  instead of a frozen button.
- **Copy / Save as .txt** buttons next to the output, for pasting the
  summary into a ticket or report elsewhere.

**Dark theme fix:** the report's CSS originally hardcoded dark navy
headings and dark gray body text, assuming a white background — invisible
against a dark JMeter theme, the same class of bug fixed elsewhere in this
listener but missed here since this component renders HTML rather than
being hand-painted. Now derives its colors from `Panel.background` the
same way the rest of the dashboard does, and the `JEditorPane`'s own Swing
background is set to match so there's no mismatch at the component level.

**Layout note:** `JTabbedPane` sizes its tab strip to fit the tallest tab
(Live Dashboard, with its charts and table), so the AI Chat tab initially
inherited that same oversized height, pushing the input box down to the
bottom of a mostly-empty area instead of sitting right below the
transcript. Fixed by giving the transcript a fixed preferred height and
anchoring the whole transcript+input block at the top of the tab instead
of letting it stretch — any leftover inherited height just becomes blank
space below it now, which is harmless.

## Making "AI is working" more prominent

Both Enhance Observations with AI and AI Chat now give clearly noticeable feedback
while waiting on Gemini CLI, not just a quiet status-label text change:

- **An indeterminate progress bar** appears next to the status label the
  moment a request starts, and disappears when it finishes.
- **The status label itself turns bold and orange** ("Thinking\u2026
  (Ns)", counting up in real time) while running, green ("Done") on
  success, red ("Error") on failure \u2014 reverting to normal weight/color
  once finished.
- **AI Chat specifically** also drops an animated "Gemini: *thinking...*"
  bubble directly into the transcript (amber left border, so it's visually
  distinct from a completed response) right where you're already looking,
  which then gets replaced by the real answer once it arrives \u2014 the
  same pattern as a typing indicator in any chat app.

## Mouse wheel not scrolling in the report/chat/help panes

A documented Swing quirk: `JEditorPane`'s HTML view can swallow mouse
wheel events without forwarding them to its enclosing `JScrollPane`. Fixed
by explicitly relaying wheel events to the scrollbar ourselves
(`installMouseWheelScrolling`), applied to all three `JEditorPane`
instances (Results Summary, AI Chat transcript, Help) rather than relying
on default forwarding. One caveat worth knowing: if a pane's content is
genuinely shorter than its visible area (e.g. a short chat with only one
or two messages), there's nothing to scroll regardless of this fix \u2014
that part is correct, expected behavior, not a bug.

## AI Chat tab

A fourth tab, **AI Chat**, for asking follow-up questions beyond the
canned summary \u2014 e.g. "Why might T01_Token_POST be slow given my test
plan?" or "Which transactions are breaching my SLA?" Reuses the same
Gemini CLI subprocess mechanism as Enhance Observations with AI (same command,
configured in the Results Summary tab), with a couple of differences:

- **Includes the actual .jmx test plan** as context, when the "Include
  .jmx test plan" checkbox is on (default: on) \u2014 read from whatever
  file is currently loaded/saved, via `GuiPackage.getInstance().getTestPlanFile()`.
  If the plan hasn't been saved yet, this comes back empty and the prompt
  says so rather than silently omitting it. Very large test plans are
  truncated at 200,000 characters so a single huge `.jmx` doesn't blow up
  every turn.
- **Each message is a fresh, self-contained subprocess call** rather than
  a persistent session \u2014 Gemini CLI's headless mode is documented as
  one-shot (prompt in, response out, process exits), so there's no
  reliable way to keep a real interactive session open across turns. To
  simulate a conversation, the last few exchanges (`MAX_CHAT_HISTORY_TURNS`,
  currently 3) are re-sent as plain-text context on every new question.
  This means responses get slightly slower and use more of your Gemini
  quota as a conversation grows \u2014 acceptable for occasional follow-up
  questions, not meant for long chat sessions.
- **Same privacy note as everywhere else in this tab set applies, more so
  here**: with the checkbox on, your *entire test plan XML* (not just
  summary numbers) goes to Google's Gemini API. Test plans can contain
  hardcoded credentials, internal hostnames, or other sensitive
  configuration in HTTP Header Managers, Auth Managers, etc. \u2014 review
  what's actually in your `.jmx` before leaving that checkbox on, or turn
  it off to send only the results summary.
- Plain **Enter sends** the message; **Shift+Enter** inserts a newline for
  multi-line questions.

## Formal HTML report (data tables + AI analysis together)

The Results Summary tab is now a real formal report, not a plain-text
scroll box:

- **Rendered as HTML**, not raw markdown/plain text — headings, bold,
  bullet lists, and actual `<table>`s, via a Swing `JEditorPane`.
- **Data-driven sections that need no AI call at all**, computed straight
  from the test data: Overall Totals, Top 10 Slowest Transactions, Top 10
  Slowest Requests, Failed Transactions & Requests (any node with
  errors, sorted by error count), Top 5 Error/Response Codes (excludes
  2xx), and SLA Breaches (only shown if an SLA threshold is set).
- **The AI Analysis section** (Gemini CLI's response) is appended after
  those, converted from markdown to HTML by a small dependency-free
  converter (`MarkdownLite.java` — headings, bold, inline code, bullet/
  numbered lists, horizontal rules; nested list indentation isn't
  supported, a deliberate simplification).
- **Graceful degradation**: if Gemini CLI fails for any reason, the data
  sections still render in full — only the AI section is replaced with an
  explanation of what went wrong (including the same trusted-directory
  diagnosis as before). The formal report never depends on the AI call
  succeeding.
- **CLI startup noise is filtered out** (e.g. "256-color support not
  detected", "Ripgrep is not available") before the response is shown or
  saved — that's terminal chatter captured incidentally by merging
  stderr into stdout, not part of the actual analysis.
- **"Save Report as HTML..."** writes the exact rendered report (data
  tables + AI section) to a `.html` file — opens directly in any browser,
  where the tables render more crisply than Swing's fairly basic HTML
  support manages. **"Copy AI Text"** copies just the raw AI response
  (for pasting into Slack/a ticket, say), separate from the full report.

**Scope note:** unlike Export CSV/Excel and the AI prompt text (which
respect the live table's current Filter/Min Avg boxes and expanded rows),
these Top-N report sections are computed from the *full* underlying test
data for the current Overall-vs-selected-transaction scope. Expand/collapse
is just a table-browsing convenience and it felt wrong for a "report" to
silently omit a slow request just because nobody had expanded that row in
the Live Dashboard tab; the text Filter box is similarly meant for
interactive narrowing, not for defining what counts as "the results."

## Export to Excel (.xlsx)

A second export button, **Export Excel...**, sits next to Export CSV. It
writes a real `.xlsx` workbook — not by pulling in Apache POI (which would
mean either shading a large dependency into this jar, risking classpath
conflicts with libraries JMeter itself already bundles, or asking you to
manually drop several POI jars into `lib/ext`), but with a small,
dependency-free writer (`XlsxExporter.java`) that builds the handful of
XML parts an `.xlsx` actually is (it's just a ZIP of OOXML SpreadsheetML)
using nothing but `java.util.zip`, already in the JDK. Single jar, no new
dependency, no classpath risk.

**Parent/child clarity, so it doesn't get confused:** the exported sheet
mirrors the live table's visual language —
- Transaction rows: **bold**, light-gray fill.
- Sub-request rows: indented one level in from the transaction column,
  prefixed with `└`, plain weight, no fill.
- Rows breaching the SLA (same P95-vs-threshold check as the table's red
  highlight) get a red fill — for both transactions and their sub-requests.

The sheet also includes a small report header (title, and a line stating
the export's scope — Overall or a specific selected transaction — plus the
export timestamp and current SLA threshold, if any), frozen so the column
headers stay visible while scrolling, and sensible column widths set from
the start.

Same semantics as Export CSV: it exports exactly what's currently visible
in the table — respecting the Filter/Min Avg boxes, the current sort, and
which transactions are expanded — not the full unfiltered dataset.

## Remote Run tab

Triggers a non-GUI JMeter run on a remote host over SSH — the same
`JVM_ARGS="..." .../bin/jmeter -n -t ... -l ... -j ...` someone would
otherwise type by hand in a terminal — and optionally live-monitors its
progress by feeding the growing result file into this same dashboard's
existing charts and table, the same way a locally-attached listener
would.

**Connects with either a password or an SSH key**, chosen per
connection. Host key checking is disabled for simplicity in this first
version (`StrictHostKeyChecking=no`) — a real tradeoff worth knowing
about rather than discovering later: it accepts any host key without
comparing it against a known_hosts file, which is fine for a trusted,
internal host reached directly, but isn't a substitute for host key
verification on anything reachable over an untrusted network. A
password, when used, is held only in memory for the life of the
connection — never written to disk or logged.

**Password auth also attempts keyboard-interactive, not just plain SSH
"password."** These are genuinely different SSH authentication
methods, even though many servers use keyboard-interactive to do
exactly the same thing: prompt for the account's password, routed
through PAM. A server configured with `PasswordAuthentication no` in
`sshd_config` (common, especially on hardened corporate servers)
rejects plain password auth outright while still accepting the same
correct password through keyboard-interactive — confirmed as a real,
reported connection failure, not a hypothetical, before fixing it this
way. If password auth still fails after this fix, the server likely
only accepts a key (or something further out of this plugin's scope,
like Kerberos/GSSAPI single sign-on or an SSH agent such as Pageant,
neither of which this version supports).

**Stopping a run** uses JMeter's own `shutdown.sh` (graceful) or
`stoptest.sh` (abrupt), executed *on the remote host itself* through
the same SSH session — not by connecting to its shutdown port (4445 by
default) directly from this plugin, which would not work: that
listener only accepts connections from the same host the non-GUI
JMeter process is running on, confirmed before designing this rather
than assumed.

**Live monitoring** re-downloads the whole remote result file each
poll (not an incremental byte range — the CSV's header line only
exists once, at the start of the file) and reads it using JMeter's own
`CSVSaveService.processSamples()`, the same method JMeter itself uses
to replay a saved JTL file — so this isn't a hand-rolled CSV parser
guessing at column order or quoting rules. A wrapper skips however
many rows were already processed in earlier polls, so nothing gets
double-counted; this is a row-count skip, not a timestamp comparison,
since samples from different threads completing near-simultaneously
aren't strictly guaranteed to be in perfect timestamp order in the
file. Re-downloading the whole file each poll is a real, known scaling
limit — a very large result file or a very long-running test will make
each poll slower and heavier on bandwidth, worth knowing rather than
discovering partway through a long run.

**Dependency note:** this tab bundles `com.github.mwiede:jsch` (an
actively-maintained fork of the classic JSch SSH library) directly into
the plugin jar — nothing extra to install separately. Deliberately not
the original `com.jcraft:jsch`: that one was last released in 2018 and
can't read the key format `ssh-keygen` has produced by default since
OpenSSH 7.8, so key-based auth would fail outright against any
reasonably current SSH server.

## Remote Run: browsing and saved profiles

**Browse… buttons** next to each remote path field (JMeter bin dir, .jmx
path, output CSV, log path) open a remote file browser over the
already-connected session's own SFTP channel — navigate directories,
double-click to descend into one, single-click anything to fill the
path field without navigating away from it. If nothing's connected
yet, clicking Browse connects first (using whatever's currently in the
connection fields) rather than requiring a separate, easy-to-forget
"Connect" step beforehand.

**Profile: dropdown** saves a full connection + remote-test
configuration under a name, so switching between different hosts (or
different test setups on the same host) is a dropdown pick rather than
retyping every field each time. **Deliberately never saved: the
password or key passphrase** — same principle as everywhere else in
this plugin, a credential is held only in memory for the life of a
connection. Loading a profile restores everything else; the
password/passphrase fields are left exactly as they were, since a
saved profile never implies a credential was saved alongside it.
Persisted to `~/.jmeter-insightdashboard/remote-run-profiles.json`.

## Live monitoring: thread group shows correctly now; sub-request tree does not (and can't, from CSV)

**Fixed:** live-monitored remote runs showed "(unknown thread group)"
in the Thread Group filter — that was literally this plugin's own
fallback string, not a mystery value. `JMeterContextService`'s live
thread-group lookup only resolves on a genuine JMeter sampling thread;
live monitoring reconstructs samples from a CSV file on a background
Swing thread that never actually ran as one, so that lookup always
returned null there. Fixed by falling back to parsing the sample's own
thread name, which JMeter's standard naming convention embeds the
group name into directly (`"Thread Group 1 1-3"`) and which — unlike
the live context — **is** present in CSV data.

**Now built, with an honest tradeoff stated up front:** an **Output
format: CSV / XML** choice next to the live monitoring controls. CSV
stays the default — a transaction's relationship to its sub-requests
only exists as nested, in-memory object references during a live run,
and JMeter's standard CSV output is a flat table with no column that
encodes "this row's parent is that other row," so it can never show a
tree. XML output *can* preserve that nesting — but JMeter's own FAQ
states plainly that "XML output files need a bit of editing to
maintain well-formedness," meaning a growing XML file **isn't valid,
parseable XML until the test actually finishes** and its root element
gets properly closed. So this is a genuinely different kind of "live"
monitoring for XML mode: live stats aren't shown progressively while
the run is in progress the way CSV mode does; instead, the moment the
remote process exits, this plugin downloads the now-complete XML file
and does one single load using JMeter's own `SaveService.
loadTestResults()` — the same mechanism JMeter's own "load results
from file" feature uses — reconstructing every sample with its
sub-results correctly nested, all at once. Pick CSV for genuinely
live, progressive numbers while a test runs; pick XML when the full
transaction tree matters more than seeing numbers tick up during the
run itself.

## CSV showing nothing after an XML run (or any reused output path)

**Fixed:** reusing the same output path across two runs — switching
between CSV and XML, or even two plain CSV runs in a row — could leave
live monitoring showing nothing at all for the second run. The actual
cause: JMeter's own documented behavior is to **append** to an
existing output file rather than overwrite it. Reusing a path from an
earlier run meant the new run's data got appended after whatever was
already there — mixed XML-then-CSV content in the worst case, or just
stale rows ahead of fresh ones even between two CSV runs — and
`CSVSaveService.processSamples()` understandably can't make sense of
that. Fixed by deleting any existing file at the output and log paths
before each run starts, so every run genuinely begins with a fresh
file regardless of what ran there before.

## CSV + tree: tried, removed

A best-effort feature once lived here that inferred the transaction
tree from CSV timing alone (a transaction's time window containing its
sub-requests' windows), meant to give CSV mode a form of the tree XML
mode provides exactly. Removed after real use showed it producing
incorrect groupings often enough not to trust — inference from timing
alone turned out to be less reliable in practice than the theory
suggested, even after testing the core algorithm against several
scenarios beforehand. If the transaction tree matters, XML mode (see
above) remains the reliable way to get it; CSV mode is for live,
flat updates only now, as it was originally.

## Remote Run layout: collapsible, scrollable, properly-sized Browse buttons

**Fixed the actual cause of the cramped look**, not just a symptom: the
layout never set `weightx` on any `GridBagLayout` column, so when a
field's column and a Browse button's column competed for space, the
button's own text-plus-padding naturally made its column wider — with
nothing telling the layout to give the *field* the extra room instead.
Fields now get `weightx = 1`; labels and buttons stay at `0` (and get
their own compact margin), so a path field actually uses the space
instead of being squeezed into a sliver next to an oversized button.

**Remote host**, **Remote test**, and **Run** are each now collapsible
— click the header (▼/▶) to show or hide a section's fields when they
aren't currently needed. The whole configuration area is wrapped in
its own scroll pane too, independent of the output log below, so
expanding every section at once on a shorter window scrolls instead of
clipping or forcing everything to fight for the same fixed space.

## Remote Run layout: side by side, less wasted space

**Remote host** and **Remote test** now sit side by side instead of
stacked full-width — the previous layout left most of a wide JMeter
window empty to the right of every field, one section at a time. The
auth row (password/SSH key, key file, passphrase) was also split from
one long, crammed line into separate rows, since it would otherwise
wrap unpredictably in a narrower half-width column. Run controls and
the remote output log stay full-width below, since Start/Stop and a
scrolling log genuinely benefit from the extra room.

## Upload the open .jmx and auto-name outputs

**"Upload the currently-open .jmx here before running"** (checked by
default) copies the locally-open test plan to the remote path in
"Remote .jmx path" right before starting the run — no need for the
.jmx to already exist on the remote host beforehand. The local file's
path comes from `GuiPackage.getTestPlanFile()`, JMeter's own standard
way of knowing what's currently open; if the test plan hasn't been
saved yet, there's nothing to upload and this is reported clearly
rather than silently skipped or run against a stale remote copy.

**"Auto-fill paths from currently-open .jmx + timestamp"** names the
remote .jmx, output CSV, and log using the open test plan's own
filename plus a timestamp, placed alongside whatever's in "Remote
JMeter bin dir" — matching the convention already visible in a manual
git-bash workflow (script and outputs living together under the bin
directory). Deliberately uses an unambiguous `yyyyMMdd_HHmmss` format
rather than a two-digit `DD_MM_YY`/`MM_DD_YY` style, which is
genuinely ambiguous to read back later without knowing which
convention was used. Also turns on the upload checkbox above, since
generating a new remote .jmx path only makes sense together with
actually putting the file there.

## Filter by thread group

With multiple thread groups in one test plan, a **Thread Group:**
dropdown next to the name filter lets the table show just one thread
group's own numbers instead of every thread group combined.

**Why this matters, not just as a convenience:** without it, two thread
groups that happen to share a transaction name (say, both have their
own "01_Launch") get silently merged into one row — the avg/min/max and
percentiles shown are computed across *both* thread groups' samples
together, as if they were one logical transaction, with nothing in the
table indicating that happened. Picking a specific thread group here
shows that thread group's own numbers only, correctly kept separate
from the start — not derived after the fact by trying to "un-merge"
already-combined statistics, which isn't something that can be done
accurately once the merge has already happened. This needed its own,
separately-tracked data captured alongside the existing combined view,
not a filter layered on top of it. "All thread groups" keeps the
original, pre-existing combined behavior as the default.

This is JMeter's own listener-scoping behavior interacting with this
dashboard's label-based aggregation, not a bug introduced by either
one — worth knowing if a single dashboard listener is placed at the
Test Plan level (seeing every thread group) rather than nested inside
one specific thread group (seeing only that one, with no filter needed
at all since there's nothing else to filter from).

## Click a row to focus the charts on it

Click any row in the table (a transaction or a sub-request) and the three
line charts and the code breakdown all switch to that row's own data
instead of the overall aggregate:

- Each chart's legend gains a " \u2014 <label>" suffix so it's obvious what
  you're looking at.
- A "Viewing: ..." label above the charts states it explicitly too, with a
  **Show Overall** button (enabled only while something's selected) to
  reset back to the aggregate view.
- The Active Threads overlay stays global on every chart regardless of
  selection — thread count is a process-wide figure, so scoping it to one
  transaction wouldn't mean anything.
- The Response/Error Codes breakdown now also counts codes from
  **sub-requests**, not just top-level transaction samples — previously it
  only reflected the parent transaction's own response code, which (when
  using Transaction Controllers) usually isn't very informative on its
  own.

This drill-down data is tracked per label from the start of the test, so
selecting a row shows its history over the whole run so far, not just from
the moment you clicked.

## Code breakdown label truncation and tooltip

The response/error code labels were being cut off at a fixed 15 characters,
which for two different "Non HTTP response code: ..." failures cut off
before the part that actually distinguishes them. Fixed two ways:
- Truncation is now based on actual available pixel width (using the
  panel's real font metrics), not a fixed character count, so it uses
  whatever width the panel actually has post-resize.
- Hover over a bar to see a tooltip with the full, untruncated code text
  and its count.

## Resizing / width not adjusting

JMeter wraps each listener's GUI panel in a `JScrollPane`. A plain `JPanel`
doesn't tell that scroll pane to match its width, so resizing the JMeter
window just added a horizontal scrollbar instead of actually resizing the
charts/table. Fixed by implementing `Scrollable` and returning `true` from
`getScrollableTracksViewportWidth()`, so the panel — and everything inside
it (stat cards, the 2x2 charts, the table) — always stretches to match the
available width. Height deliberately does NOT track the viewport, so
vertical scrolling still works normally once the dashboard's content is
taller than the space available.

## Error rate chart and response/error code breakdown

The charts area is now a 2x2 grid:

- **Error Rate (%)** — a third rolling line chart, same style as the other
  two (with the active-thread overlay too), showing the percentage of
  failed samples per second over the last ~2 minutes.
- **Response / Error Codes** — a live horizontal bar chart of
  `SampleResult.getResponseCode()` values across the whole test (not
  scoped to a single transaction), color-coded by status class: green
  2xx, blue 3xx, orange 4xx, red 5xx, and purple for anything non-HTTP
  (connection refused, timeouts, etc. — JMeter reports these through the
  same field with a descriptive string instead of a numeric code). Shows
  the top 8 codes by count; anything beyond that is folded into "Other" so
  a long tail of distinct error messages doesn't take over the chart.
  Non-HTTP messages longer than 40 characters are truncated before
  counting, since the same underlying failure (e.g. connection refused)
  can otherwise show up as several near-duplicate "different" codes if the
  exception message text varies slightly between occurrences.

This breakdown is global across all transactions. If you want it scoped to
just the currently-filtered transaction in the table instead, that's a
reasonable next step — let me know and I'll wire the two together.

## Throughput column and configurable columns

- **Throughput (req/s) column** — computed per label the same way JMeter's
  own Aggregate Report does: samples divided by that label's own active
  window (its first sample's start to its last sample's end), not the
  whole test's elapsed time. So a transaction that only ran during part of
  the test gets an accurate per-second rate for the time it was actually
  running, rather than being diluted by idle time elsewhere in the test.
  Sub-request rows get their own throughput too, computed the same way.
- **Columns \u25BC button** — next to Export CSV. Opens a checklist of every
  column except Label (which always stays visible, since it's the row
  identifier); toggle any of them off to declutter the table down to just
  what you care about for a given run. Re-checking a column reinserts it in
  its original position among whatever else is currently visible, rather
  than always appending it at the far right.

## Failures tab: JSON/XML response bodies shown pretty-printed

Request and Response bodies in the Failures tab are now re-indented when
they look like JSON or XML/HTML, instead of showing a single unformatted
blob. A minified `{"error":"not found","code":404}` response becomes
properly indented, multi-line JSON; an XML/HTML response gets the same
treatment, with a leaf element containing only text (like
`<message>failed</message>`) kept on one line rather than needlessly
spread across three.

Detection is deliberately conservative: it checks the actual
`Content-Type` response header first — a direct signal from the server —
falling back to checking whether the content starts with `{`, `[`, or
`<` only when that header isn't available. Either way, a structural
sanity check (matching braces/brackets for JSON, recognizable tags for
XML) has to pass before anything gets reformatted; anything that doesn't
confidently look like one of these two formats is shown completely
unchanged, exactly as before. This errs on the side of showing raw,
unformatted text over risking mangled output on something that only
partially resembles JSON/XML.

No external JSON/XML library involved — a single careful character scan
for each, correctly leaving the content of quoted strings/text nodes
untouched (so a URL, an escaped quote, or literal `{`/`<` characters
inside an actual string value don't get misread as structural
JSON/XML syntax). Validated against 19 cases before shipping, including
nested arrays-of-objects, Unicode inside strings, and already-formatted
input not getting double-indented.

## Fixed: sections 1–4 going blank after an AI response arrives

**Reload Data** button sits next to Clear Observations in the Results
Summary toolbar — re-populates sections 1–4 from current test data on
demand, without waiting for a test to finish. Resets section 5 back to
"not yet generated" too, since any existing AI analysis was written
against whichever data was showing before.

**The actual bug this surfaced:** pressing JMeter's own Run button clears
every listener's data automatically — standard JMeter behavior,
independent of anything in this plugin. If that happened while an
"Enhance Observations with AI" request was still in flight (easily
possible; these can take several seconds), the response would arrive and
get applied on top of data that had already been wiped — sections 1–4
showing "None." while section 5 still displayed AI analysis of the data
that used to be there. Fixed with a generation counter: every AI request
captures the current data generation when it starts, and checks it again
when the response arrives. If the generation has changed in between, the
response is discarded with a clear "test was re-run while waiting"
message instead of being shown against mismatched, now-empty sections.

## Fixed: other tabs' content pushed off-screen by Live Dashboard's own height

The whole listener implements `Scrollable` so JMeter's outer scroll pane
lets Live Dashboard's own content (stat cards + 2x2 charts + table) grow
taller than available space when it needs to. That setting used to apply
globally, though — so switching to AI Chat, Results Summary, Failures, or
Help inherited Live Dashboard's height requirement too, even though each
of those tabs already fits its own content fine and has its own bottom-
pinned input/button bar that depends on actually being constrained to
the real visible height. The practical symptom: AI Chat's message box
could end up pushed off-screen, needing a scroll its own content never
required, just because Live Dashboard (a different tab entirely) happened
to need more room. Fixed by making this per-tab: only Live Dashboard
(the one tab that actually needs it) is now allowed to exceed the
viewport height; every other tab stays properly constrained.

## Dark theme support

All the hand-drawn parts (stat cards, chart backgrounds/gridlines/axis
text, and SLA row highlighting) now derive their colors from JMeter's
actual Look & Feel via `UIManager`, rather than a hardcoded light palette.
If you're running JMeter with a dark theme (Options > Look and Feel), the
dashboard's panels, cards, and charts follow it automatically instead of
showing white boxes against a dark UI.

How it decides "dark vs. light": it reads `UIManager.getColor("Panel.background")`
(and `Table.background`/`Table.foreground` for the table, which the JTable
already picks up on its own) and computes the perceived luminance of that
color. Below a threshold it's treated as dark and uses light gridlines/text
and a muted dark-red SLA breach highlight; otherwise it uses the original
light-mode look. This only reads colors at construction time — if you
switch Look & Feel while JMeter is already running (uncommon; most themes
need a restart to fully apply anyway), you'll need to remove and re-add the
listener (or restart JMeter) to pick up the new palette.

## File save/export dialogs default to your test plan's folder

Save Report as HTML, Export CSV, and Export Excel all open their file
chooser in the current test plan's own directory — the closest thing
JMeter has to a "project folder" — rather than wherever JMeter's own
working directory happens to be. If no test plan is open/saved yet, they
fall back to the platform's own default location.

## Saved HTML report is always light-themed, independent of JMeter's own theme

The in-app Results Summary tab matches JMeter's current Look & Feel (dark
or light), same as the rest of Insight Dashboard — but **Save Report as
HTML** always writes a fixed light theme instead, laid out as an actual
document (max-width, margins, a visible page edge on a muted background)
rather than edge-to-edge like the in-app view. This is deliberate: the
saved file gets opened in a browser by whoever it's shared with, so it
shouldn't depend on what theme happened to be set on the machine that
generated it — a light, properly-padded report reads better shared or
printed than inheriting someone's personal dark-mode preference.

## Collapsible charts, click-to-sort on the Label column

**"Hide Charts" / "Show Charts"** sits at the top-left of the chart
grid, next to the "Viewing: X" context label. Collapsing it frees that
space for the table below — useful once you're mainly watching the
per-request stats rather than the graphs. The context label and "Show
Overall" button stay visible either way, since they're also relevant to
whichever row is currently focused.

**Clicking the "Label" column header** sorts the table alphabetically by
that column — click again to reverse direction. This reuses the exact
same sort mechanism as the "Sort by:"/"▼ Desc" controls in the bar above
the table (selecting "Label" there and toggling direction does the same
thing); the header click is just a one-click shortcut for that specific
column, not a separate sort system. Deliberately not a native
sortable-table-header click-anywhere (`setAutoCreateRowSorter`): this
table has a parent/child tree structure (transactions expand to show
their sub-requests), and a native row sorter would scramble that
hierarchy the same way it would for any table with nested rows — the
other columns still need the dropdown for that reason.

## Percentiles, SLA highlighting, thread-count overlay, CSV export

Four more additions on top of the tree/filter/sort table:

- **Percentiles (P90/P95/P99)** — three new table columns. Since retaining
  every single response time for a 100k+ sample run isn't practical, each
  transaction/request keeps a bounded 2000-item reservoir sample (Algorithm
  R reservoir sampling), and percentiles are computed from that. This is a
  statistically valid estimate, not an exact value — accurate enough to
  spot tail-latency problems, but don't expect it to match a report built
  from the full dataset to the last millisecond.
- **SLA threshold** — type a value into the **SLA - P95 (ms)** box. It draws
  a dashed red line on the response-time chart at that value, and any
  transaction row whose **P95** exceeds it gets a light red background in
  the table (checked against P95 rather than the average, since a
  transaction can look fine "on average" while still having a bad tail —
  P95 is the more realistic SLA signal).
- **Active thread count overlay** — both charts now also plot active
  thread count (a JMeter-wide figure via `JMeterContextService`, not scoped
  to just this Thread Group) as a gray line against its own right-hand
  axis, so you can visually correlate response time / throughput changes
  with load ramp-up.
- **Export CSV** — a button next to the filters. Writes exactly what's
  currently visible in the table (respecting your filter, sort, and
  expanded rows) to a CSV file you choose via a save dialog. Handy for
  grabbing a mid-run snapshot without waiting for the test to finish.

## Extending this

- **Percentiles** — `LabelStats` only tracks min/avg/max today. For p90/p95
  you'd need to retain response times (e.g. a bounded reservoir sampler, or
  a streaming quantile estimator like t-digest) rather than a single sum.
- **Charts** — add an inline `<canvas>` + a small embedded JS snippet (no
  external network calls, so it still works air-gapped in CI).
- **CSV/JSON export** — add a second write alongside `writeHtmlReport`.
- **Placement matters** — put this element directly under the Test Plan
  (not inside a Thread Group) if you have multiple thread groups and want
  one merged report instead of one per group.
- **Performance** — `sampleOccurred` fires once per sample from every
  sampler thread. Keep it allocation-light and non-blocking (as written, it
  only touches an in-memory `ConcurrentHashMap` — no I/O per sample).

## Troubleshooting

- **Listener doesn't show up in the Add menu** — check JMeter's startup log
  (`jmeter.log`) for a `ClassNotFoundException`/`NoClassDefFoundError`;
  usually means the jar wasn't in `lib/ext` or was built against a
  different JMeter major version than the one you're running.
- **Report is empty in `-n` mode** — double check the XML was pasted with
  the exact `guiclass`/`testclass` names above, and that `enabled="true"`.
