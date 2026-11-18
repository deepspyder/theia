# Crop, vignette and export metadata

Mode: Operate. Keep the existing rounded darkroom and top edit queue.

Crop adds cinema presets 2.39:1 and 1.85:1 alongside the existing ratios and an
explicit Custom mode. The selection stays in source-normalized coordinates.
Drag inside moves the selection; drag a corner resizes. Each pointer delta
advances the drag origin, so continuous motion accumulates rather than snapping
back to the first location. Callbacks and ratio use latest Compose state. A
fixed preset constrains corner resize to that aspect; moving does not clear the
preset. Custom removes that constraint. Overscan mode remains a separate
engine-defined centred film gate and is not the free crop tool. A full-image
selection cannot move beyond the source: shrink it or choose a ratio first.
The crop surface has 28dp horizontal safe gutters so corner drags do not
start inside Android's system Back gesture zone.
Cancel restores the pre-entry geometry; Done uses the existing render path.

Responsive correction: when the crop workspace is short and wide, keep the
existing top toolbar and move ratio/reset/help into a vertically scrollable
200–280dp sidebar so the source or finished image fits the available height.
Portrait and tablet layouts retain the incumbent vertical crop controls.

Vignette is a Layer 2 finishing effect in Grade with amount, size and feather
(0–100). Zero amount is the exact previous behavior. It is elliptical in output
coordinates, smooth at its inner transition and does not change centre exposure.
Preview/histogram, JPEG/PNG, and streamed 16-bit TIFF call the same spatial grade
method with absolute output coordinates, including strip rows. A small gain
lookup avoids a power calculation per output pixel. Settings use sidecars,
undo/redo, normal settings sync and batch export. DI TIFF remains ungraded.

Export shows independent Capture information and Location information switches
for JPEG, PNG and TIFF. Capture defaults on; GPS defaults off, matching the
previous absence of exported location. Both are remembered locally and included
in named recipes; older recipes retain the defaults. Disabled during processing.
Capture allowlists camera/lens, capture dates, ISO, exposure, aperture, focal
length, flash and white balance. Location copies only existing GPS coordinates,
altitude, timestamps and image direction. For MediaStore originals on Android 10+, enabling GPS requests only
ACCESS_MEDIA_LOCATION when needed and tries require-original access; inaccessible
or stripped tags remain absent. It never requests or synthesizes a current location. No maker notes, embedded thumbnails or image orientation are
copied. JPEG/PNG write selected EXIF via ExifInterface; TIFF writes native
IFD/EXIF/GPS directories without rewriting the 16-bit image or ICC profile.
Rendered dimensions/orientation come from the output, not the original image.
Batch export reads each source's metadata separately.

# Decode scene curves

The Decode scene-curve entry reuses the existing CurveEditor interaction and
Operate layout while keeping its model and render stage independent from the
finished-image curve. Its controls use a -8 to +8 EV axis, with the selected
point's input and output shown in EV and the existing point editing, precision,
reset, undo/redo, cancel, and commit gestures preserved.

The entry is visibly disabled while film simulation is off; saved scene-curve
data remains intact and becomes active again when film simulation is restored.
Unknown or invalid future scene-curve data stays preserved and is surfaced as a
warning rather than being silently applied. Decode and finished-image curve
edits therefore remain isolated, and switching photos or cancelling an edit
restores the corresponding uncommitted draft.

On compact and large-font layouts, the Decode graph may be shortened by 48dp
to keep the EV help text and bottom actions inside the safe area. The finished-
image curve graph keeps its established dimensions. The same behavior is
retained in landscape and tablet layouts, with no new visual system or raster
assets introduced.

Validation uses real injected multi-event crop drags, locked resize, presets,
cancel/commit, flat-field vignette pixels and TIFF parity, serialization and
undo/redo, twelve format×capture×GPS combinations, actual engine exports and
a two-source metadata batch. Coordinates in test fixtures are synthetic.
Device evidence is emulator based; no hardware performance claim.
