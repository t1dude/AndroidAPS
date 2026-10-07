# Glucose circle face: layout and pictures

The face in `src/circle` is written by these two tools. Change the tools, not their output.

- `gen_watchface_xml.py` writes `src/circle/template/watchface.xml`: positions, slots and colours.
- `FaceArt.java` draws `background.png` (gradient, lines from the circle's centre, minute ticks)
  and `preview.png` (the whole face with sample values, for the face pickers).

Both use the same layout numbers. If you move the circle, change both.

Run from the repository root (Python 3 and JDK 17 or newer):

```
python3 wear/watchfacepush/tools/circle/gen_watchface_xml.py wear/watchfacepush/src/circle/template/watchface.xml
java -Djava.awt.headless=true wear/watchfacepush/tools/circle/FaceArt.java /tmp/circle-art
cp /tmp/circle-art/background.png wear/watchfacepush/src/circle/res/drawable-nodpi/background.png
for f in wear/watchfacepush/src/circle/res/drawable-nodpi/preview.png \
         wear/watchfacepush/src/circle/res/drawable-nodpi/preview_circular.png \
         wear/src/main/res/drawable-nodpi/watchface_circle.png \
         plugins/sync/src/androidMain/res/drawable-nodpi/circle_watchface_preview.png; do
    cp /tmp/circle-art/preview.png "$f"
done
```

The face uses Watch Face Format 2 (for goal progress, such as steps); `src/circle/AndroidManifest.xml`
sets that for this face only.

Watch Face Format wants whole numbers for the position and size of a part (`PartImage`,
`PartText`, `PartDraw`); the validator in the wear build fails otherwise.
