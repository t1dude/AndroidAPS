"""Writes the glucose circle face's watchface.xml from the layout below.

Run from the repository root:
    python3 wear/watchfacepush/tools/circle/gen_watchface_xml.py wear/watchfacepush/src/circle/template/watchface.xml

Edit the layout here, not in watchface.xml. If the circle moves, change FaceArt.java to match and
draw the background again (see README.md).
"""
import math
import sys

ACCENT = "#ffa9b4ff"
LABEL = "#ffc6cddd"
TRACK = "#1affffff"

# Glucose circle slot: 236 px, centre (225, 168).
SLOT = 236
SX, SY = 225 - SLOT // 2, 168 - SLOT // 2
K = SLOT / 270

# Edge slots: (slotId, name, display string, start, end, bottom half, default policy)
EDGES = [
    (5, "EdgeTopLeft", "edge_top_left", 296, 340, False,
     'defaultSystemProvider="WATCH_BATTERY" defaultSystemProviderType="RANGED_VALUE"'),
    (6, "EdgeTopRight", "edge_top_right", 20, 64, False,
     'defaultSystemProvider="STEP_COUNT" defaultSystemProviderType="SHORT_TEXT"'),
    (7, "EdgeBottomRight", "edge_bottom_right", 130, 166, True,
     'primaryProvider="AAPS_WEAR_APP_ID/app.aaps.wear.complications.ReservoirComplication" primaryProviderType="RANGED_VALUE" '
     'defaultSystemProvider="EMPTY" defaultSystemProviderType="EMPTY"'),
    (8, "EdgeBottomLeft", "edge_bottom_left", 194, 230, True,
     'primaryProvider="AAPS_WEAR_APP_ID/app.aaps.wear.complications.UploaderBatteryComplication" primaryProviderType="RANGED_VALUE" '
     'defaultSystemProvider="EMPTY" defaultSystemProviderType="EMPTY"'),
]

# Dials: (slotId, name, display string, x, y, default policy)
DIAL = 84
DIALS = [
    (2, "DialLeft", "dial_left", 100 - DIAL // 2, 290 - DIAL // 2,
     'defaultSystemProvider="EMPTY" defaultSystemProviderType="EMPTY"'),
    (4, "DialRight", "dial_right", 350 - DIAL // 2, 290 - DIAL // 2,
     'primaryProvider="AAPS_WEAR_APP_ID/app.aaps.wear.complications.IobIconComplication" primaryProviderType="SHORT_TEXT" '
     'defaultSystemProvider="EMPTY" defaultSystemProviderType="EMPTY"'),
]

RANGE = ("clamp(([COMPLICATION.RANGED_VALUE_VALUE] - [COMPLICATION.RANGED_VALUE_MIN]) / "
         "([COMPLICATION.RANGED_VALUE_MAX] - [COMPLICATION.RANGED_VALUE_MIN]), 0, 1)")


def polar(r, deg, cx=225, cy=225):
    a = math.radians(deg)
    return cx + r * math.sin(a), cy - r * math.cos(a)


def f(n):
    return f"{n:g}" if float(n).is_integer() else f"{n:.1f}"


def font(size, color="#ffffffff", weight="NORMAL"):
    return f'<Font family="SYNC_TO_DEVICE" size="{f(size)}" weight="{weight}" slant="NORMAL" color="{color}">'


def text_part(x, y, w, h, size, expression, color="#ffffffff", weight="NORMAL", indent=0):
    p = " " * indent
    return (f'{p}<PartText x="{f(x)}" y="{f(y)}" width="{f(w)}" height="{f(h)}">\n'
            f'{p}    <Text align="CENTER" ellipsis="TRUE">\n'
            f'{p}        {font(size, color, weight)}\n'
            f'{p}            <Template>%s<Parameter expression="{expression}" /></Template>\n'
            f'{p}        </Font>\n'
            f'{p}    </Text>\n'
            f'{p}</PartText>')


def edge_slot(slot_id, name, display, a0, a1, bottom, policy):
    span = a1 - a0
    ix, iy = polar(200, a0 - 8)
    icon = 20
    r_text = 192 if bottom else 188
    if bottom:
        circular = (f'startAngle="{a1}" endAngle="{a0}" direction="COUNTER_CLOCKWISE"')
    else:
        circular = f'startAngle="{a0}" endAngle="{a1}" direction="CLOCKWISE"'
    label = f'''                <PartText x="0" y="0" width="450" height="450">
                    <TextCircular centerX="225" centerY="225" width="{2 * r_text}" height="{2 * r_text}" {circular} align="CENTER" ellipsis="TRUE">
                        {font(15, LABEL, "SEMI_BOLD")}
                            <Template>%s<Parameter expression="[COMPLICATION.TEXT]" /></Template>
                        </Font>
                    </TextCircular>
                </PartText>'''
    icon_part = f'''                <Condition>
                    <Expressions>
                        <Expression name="hasIcon"><![CDATA[[COMPLICATION.MONOCHROMATIC_IMAGE] != null]]></Expression>
                    </Expressions>
                    <Compare expression="hasIcon">
                        <PartImage x="{round(ix - icon / 2)}" y="{round(iy - icon / 2)}" width="{icon}" height="{icon}" tintColor="{ACCENT}">
                            <Image resource="[COMPLICATION.MONOCHROMATIC_IMAGE]" />
                        </PartImage>
                    </Compare>
                </Condition>'''
    return f'''        <ComplicationSlot
            name="{name}"
            slotId="{slot_id}"
            displayName="@string/{display}"
            isCustomizable="TRUE"
            supportedTypes="RANGED_VALUE SHORT_TEXT EMPTY"
            alpha="255"
            x="0" y="0" width="450" height="450">
            <Variant mode="AMBIENT" target="alpha" value="0" />
            <DefaultProviderPolicy {policy} />
            <BoundingArc centerX="225" centerY="225" width="410" height="410" thickness="56" startAngle="{a0 - 16}" endAngle="{a1 + 4}" />
            <Complication type="RANGED_VALUE">
                <PartDraw x="0" y="0" width="450" height="450">
                    <Arc centerX="225" centerY="225" width="414" height="414" startAngle="{a0}" endAngle="{a1}">
                        <Stroke color="{TRACK}" thickness="7" cap="ROUND" />
                    </Arc>
                    <Arc centerX="225" centerY="225" width="414" height="414" startAngle="{a0}" endAngle="{a0}">
                        <Stroke color="{ACCENT}" thickness="7" cap="ROUND" />
                        <Transform target="endAngle" value="{a0} + {span} * {RANGE}" />
                    </Arc>
                </PartDraw>
{icon_part}
{label}
            </Complication>
            <Complication type="SHORT_TEXT">
{icon_part}
{label}
            </Complication>
            <Complication type="EMPTY" />
        </ComplicationSlot>'''


def dial_slot(slot_id, name, display, x, y, policy):
    d = DIAL
    c = d / 2
    plate = f'''                <PartDraw x="0" y="0" width="{d}" height="{d}">
                    <Ellipse x="0" y="0" width="{d}" height="{d}">
                        <Fill color="#ff10141c" />
                        <Stroke color="#1fffffff" thickness="1.5" />
                    </Ellipse>
                    <Arc centerX="{f(c)}" centerY="{f(c)}" width="70" height="70" startAngle="220" endAngle="500">
                        <Stroke color="#17ffffff" thickness="4" cap="ROUND" />
                    </Arc>
                </PartDraw>'''
    icon_big = f'''                <PartImage x="22" y="22" width="40" height="40" tintColor="{ACCENT}">
                    <Image resource="[COMPLICATION.MONOCHROMATIC_IMAGE]" />
                </PartImage>'''
    short_text = f'''                <Condition>
                    <Expressions>
                        <Expression name="iconAndText"><![CDATA[[COMPLICATION.MONOCHROMATIC_IMAGE] != null]]></Expression>
                        <Expression name="titleAndText"><![CDATA[[COMPLICATION.TITLE] != null]]></Expression>
                    </Expressions>
                    <Compare expression="iconAndText">
                        <PartImage x="30" y="13" width="24" height="24" tintColor="{ACCENT}">
                            <Image resource="[COMPLICATION.MONOCHROMATIC_IMAGE]" />
                        </PartImage>
{text_part(4, 38, 76, 30, 22, "[COMPLICATION.TEXT]", "#fff3f5fa", "SEMI_BOLD", 24)}
                    </Compare>
                    <Compare expression="titleAndText">
{text_part(8, 17, 68, 20, 13, "[COMPLICATION.TITLE]", ACCENT, "SEMI_BOLD", 24)}
{text_part(4, 36, 76, 30, 22, "[COMPLICATION.TEXT]", "#fff3f5fa", "SEMI_BOLD", 24)}
                    </Compare>
                    <Default>
{text_part(4, 26, 76, 32, 24, "[COMPLICATION.TEXT]", "#fff3f5fa", "SEMI_BOLD", 24)}
                    </Default>
                </Condition>'''
    return f'''        <ComplicationSlot
            name="{name}"
            slotId="{slot_id}"
            displayName="@string/{display}"
            isCustomizable="TRUE"
            supportedTypes="SHORT_TEXT RANGED_VALUE MONOCHROMATIC_IMAGE SMALL_IMAGE EMPTY"
            alpha="255"
            x="{x}" y="{y}" width="{d}" height="{d}">
            <Variant mode="AMBIENT" target="alpha" value="0" />
            <DefaultProviderPolicy {policy} />
            <BoundingOval x="0" y="0" width="{d}" height="{d}" />
            <Complication type="SHORT_TEXT">
{plate}
{short_text}
            </Complication>
            <Complication type="RANGED_VALUE">
{plate}
                <PartDraw x="0" y="0" width="{d}" height="{d}">
                    <Arc centerX="{f(c)}" centerY="{f(c)}" width="70" height="70" startAngle="220" endAngle="220">
                        <Stroke color="{ACCENT}" thickness="4" cap="ROUND" />
                        <Transform target="endAngle" value="220 + 280 * {RANGE}" />
                    </Arc>
                </PartDraw>
{short_text}
            </Complication>
            <Complication type="MONOCHROMATIC_IMAGE">
{plate}
{icon_big}
            </Complication>
            <Complication type="SMALL_IMAGE">
                <PartImage x="6" y="6" width="72" height="72">
                    <Image resource="[COMPLICATION.SMALL_IMAGE]" />
                </PartImage>
            </Complication>
            <Complication type="EMPTY" />
        </ComplicationSlot>'''


ring_d = 106 * K * 2
ring_xy = SLOT / 2 - ring_d / 2

head = f'''<WatchFace clipShape="CIRCLE" height="450" width="450">
    <Metadata key="CLOCK_TYPE" value="DIGITAL" />

    <!-- The glucose circle face.

         The phone overview's BG circle sits large at the top, the time and date below it, one dial on
         each side, and four optional slots along the edge of the screen: an arc that fills for a value
         with a range (watch battery, reservoir, rig battery), or a curved label for plain text (steps).

         Watch Face Format cannot draw an arc that follows glucose data, so the circle is a picture
         drawn by the wear app (GlucoseCircleComplication) in a slot the wearer cannot change. Its age
         line only moves when the picture is redrawn; GlucoseCircleUpdater asks for that each time the
         minute count changes.

         The background (gradient, lines from the circle's centre, minute ticks) is a picture too:
         res/drawable-nodpi/background.png. It is drawn for this layout; move the circle and it has to
         be drawn again.

         This file is written by wear/watchfacepush/tools/circle/gen_watchface_xml.py. Change the
         layout there and run it again; see the README next to it.

         Always-on: our process is frozen while the watch dozes (see the cwf face), so the picture
         would sit there with an age that has stopped. It is hidden then, and the runtime draws a grey
         ring outline and the reading from a text complication whose age it keeps counting itself.
         Everything else except the time is hidden too.

         Angles are in degrees, clockwise from 12 o'clock. Children of a ComplicationSlot are
         positioned relative to the slot; the edge slots cover the whole face so their arcs can use
         the face's centre, and a BoundingArc keeps their taps on the edge. -->

    <Scene backgroundColor="#ff000000">

        <PartImage x="0" y="0" width="450" height="450">
            <Variant mode="AMBIENT" target="alpha" value="0" />
            <Image resource="background" />
        </PartImage>

        <!-- Always-on ring outline, where the picture's ring is: the picture is {SLOT} px wide and
             drawn at 150 overview dp, so the ring's centre line has a radius of {f(106 * K)} px. -->
        <PartDraw x="{SX}" y="{SY}" width="{SLOT}" height="{SLOT}" alpha="0">
            <Variant mode="AMBIENT" target="alpha" value="255" />
            <Ellipse x="{f(ring_xy)}" y="{f(ring_xy)}" width="{f(ring_d)}" height="{f(ring_d)}">
                <Stroke color="#ff6e6e6e" thickness="4" />
            </Ellipse>
        </PartDraw>

        <!-- The circle picture. Tapping it opens the BG graph. -->
        <ComplicationSlot
            name="GlucoseCircle"
            slotId="0"
            displayName="Glucose circle"
            isCustomizable="FALSE"
            supportedTypes="SMALL_IMAGE EMPTY"
            alpha="255"
            x="{SX}" y="{SY}" width="{SLOT}" height="{SLOT}">
            <DefaultProviderPolicy
                primaryProvider="AAPS_WEAR_APP_ID/app.aaps.wear.complications.circle.GlucoseCircleComplication"
                primaryProviderType="SMALL_IMAGE"
                defaultSystemProvider="EMPTY"
                defaultSystemProviderType="EMPTY" />
            <BoundingOval x="0" y="0" width="{SLOT}" height="{SLOT}" />
            <Complication type="SMALL_IMAGE">
                <PartImage x="0" y="0" width="{SLOT}" height="{SLOT}">
                    <Variant mode="AMBIENT" target="alpha" value="0" />
                    <Image resource="[COMPLICATION.SMALL_IMAGE]" />
                </PartImage>
            </Complication>
            <Complication type="EMPTY" />
        </ComplicationSlot>

        <!-- The always-on reading inside the ring. Invisible while awake, but it still takes the
             taps over the circle then, so its provider opens the BG graph too - and nothing while
             dozing, so the first tap wakes the watch. -->
        <ComplicationSlot
            name="GlucoseCircleAmbient"
            slotId="1"
            displayName="Glucose circle always-on"
            isCustomizable="FALSE"
            supportedTypes="SHORT_TEXT EMPTY"
            alpha="0"
            x="{SX}" y="{SY}" width="{SLOT}" height="{SLOT}">
            <Variant mode="AMBIENT" target="alpha" value="255" />
            <DefaultProviderPolicy
                primaryProvider="AAPS_WEAR_APP_ID/app.aaps.wear.complications.circle.GlucoseCircleAmbientComplication"
                primaryProviderType="SHORT_TEXT"
                defaultSystemProvider="EMPTY"
                defaultSystemProviderType="EMPTY" />
            <BoundingOval x="0" y="0" width="{SLOT}" height="{SLOT}" />
            <Complication type="SHORT_TEXT">
{text_part(16, 70, 204, 64, 54, "[COMPLICATION.TEXT]", "#ffdcdcdc", "NORMAL", 16)}
{text_part(34, 136, 168, 32, 25, "[COMPLICATION.TITLE]", "#ffb4b4b4", "NORMAL", 16)}
            </Complication>
            <Complication type="EMPTY" />
        </ComplicationSlot>

        <!-- Fixed clock under the circle, awake and always-on. hourFormat follows the device's 12/24
             hour setting. -->
        <DigitalClock x="75" y="310" width="300" height="70">
            <TimeText align="CENTER" format="hh:mm" hourFormat="SYNC_TO_DEVICE"
                x="0" y="0" width="300" height="70">
                <Font color="#fff3f5fa" family="SYNC_TO_DEVICE" size="64" slant="NORMAL" weight="NORMAL" />
            </TimeText>
        </DigitalClock>

        <!-- AM or PM, only on a 12-hour device: TimeText cannot draw it (see the cwf face). -->
        <Condition>
            <Expressions>
                <Expression name="twelveHourClock">[IS_24_HOUR_MODE] == 0</Expression>
            </Expressions>
            <Compare expression="twelveHourClock">
                <PartText x="318" y="334" width="50" height="26">
                    <Variant mode="AMBIENT" target="alpha" value="0" />
                    <Text align="START" ellipsis="TRUE">
                        {font(18, "#ff9aa3b5")}
                            <Template>%s<Parameter expression="[AMPM_STRING]" /></Template>
                        </Font>
                    </Text>
                </PartText>
            </Compare>
        </Condition>

        <!-- Day, date and month under the time. Hidden while dozing. -->
        <PartText x="115" y="377" width="220" height="26">
            <Variant mode="AMBIENT" target="alpha" value="0" />
            <Text align="CENTER" ellipsis="TRUE">
                {font(17, "#ff9aa3b5", "SEMI_BOLD")}
                    <Template>%s %s %s<Parameter expression="[DAY_OF_WEEK_S]" /><Parameter expression="[DAY]" /><Parameter expression="[MONTH_S]" /></Template>
                </Font>
            </Text>
        </PartText>

        <!-- The two dials beside the time: empty on the left (weather fits well), insulin on board on
             the right, whose tap opens the bolus screen. A value with a range fills the ring. -->
'''

parts = [head]
for d in DIALS:
    parts.append(dial_slot(*d))
    parts.append("")
parts.append('''        <!-- The edge: watch battery, steps, reservoir and rig battery by default. An icon, when the
             complication has one, sits just before the start of its arc. -->
''')
for e in EDGES:
    parts.append(edge_slot(*e))
    parts.append("")
parts.append('''    </Scene>
</WatchFace>
''')

with open(sys.argv[1], "w") as out:
    out.write("\n".join(parts))
