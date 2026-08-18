# Cropping the motifs

These are the full-resolution originals the five sensor-card motifs come from.
They are **not** part of the build — the directory is gitignored, and everything
here is re-downloadable from the source links in
`server/src/main/resources/META-INF/resources/media/CREDITS.md`.

They are here so the crops can be made by eye, which is the one part of this that
a person does better than a script. Two attempts at finding the subject
automatically produced paper margins down both sides of one and a capercaillie with
its head cut off.

**Four, not five.** There was a boar as well, and it went: the plate turned out to
be a hunt in progress — dogs on the animal, a man with a rifle — which is not what
anybody wants faintly behind a temperature. A calm single-animal study would be
welcome as a fifth if one turns up; the zoological plates in the Iconographia
Zoologica are the place to look, since those are studies rather than scenes. Four
is enough meanwhile, because a device rarely has more sensors than that and no two
sensors on one device may share a motif.

## What the result has to be

| | |
|---|---|
| Where | `server/src/main/resources/META-INF/resources/media/` |
| Names | `moose.jpg` `roe-deer.jpg` `hare.jpg` `capercaillie.jpg` |
| Shape | whatever suits the animal — `finish.py` pads rather than crops |
| Size | anything from about 700 px wide up |
| Colour | anything; it is reduced to ink and alpha |

**Crop before scaling.** These originals are up to 3840 px wide; cropping a
downscaled copy throws away the detail that makes an engraving read as an engraving.
`finish.py` does the scaling.

**Do not worry about the shape.** An earlier version of this asked for 2.6:1 and
would centre-crop anything else, which was backwards — that number came from the
strip above a card, and a moose framed to it has no legs. Now every crop is padded
onto one canvas instead, so all four behave identically whatever you hand over.

## What makes a good one here

The motif is background, dimmed to about a tenth of its contrast and tinted to one
colour. So:

- **One large subject**, filling most of the frame. Detail below a few percent of
  the width disappears entirely at the opacity these are shown at.
- **A recognisable silhouette.** The whole point is that somebody knows which
  sensor they are looking at without reading the name, so a moose has to be a moose
  at a glance — antlers in, and no other animal competing with it.
- **No captions or plate marks.** They survive as ink and read as debris.

Paper you can ignore: `finish.py` takes the alpha channel from how much ink is at
each pixel, so the sheet becomes transparent and only the lines are drawn. That is
what stops the motif being a pale rectangle inside the card, which every earlier
attempt was — including two that tried to hide the rectangle with gradients instead
of removing it.

## Then run

    python3 tools/motif-sources/finish.py

which converts whatever is in `cropped/` to greyscale at the right size and puts
it where the build expects. Only the framing is yours to decide.
