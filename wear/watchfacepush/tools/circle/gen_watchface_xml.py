"""Writes the glucose circle face's watchface.xml. See README.md."""
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
DIAL = 108
DIAL_LEFT = (92, 280)
DIAL_RIGHT = (358, 280)
# The dial contents were laid out for 84 px; DK scales them.
DK = DIAL / 84
DIALS = [
    (2, "DialLeft", "dial_left", DIAL_LEFT[0] - DIAL // 2, DIAL_LEFT[1] - DIAL // 2,
     'defaultSystemProvider="EMPTY" defaultSystemProviderType="EMPTY"'),
    (4, "DialRight", "dial_right", DIAL_RIGHT[0] - DIAL // 2, DIAL_RIGHT[1] - DIAL // 2,
     'primaryProvider="AAPS_WEAR_APP_ID/app.aaps.wear.complications.IobIconComplication" primaryProviderType="SHORT_TEXT" '
     'defaultSystemProvider="EMPTY" defaultSystemProviderType="EMPTY"'),
]

RANGE = ("clamp(([COMPLICATION.RANGED_VALUE_VALUE] - [COMPLICATION.RANGED_VALUE_MIN]) / "
         "([COMPLICATION.RANGED_VALUE_MAX] - [COMPLICATION.RANGED_VALUE_MIN]), 0, 1)")
# Goal progress needs Watch Face Format 2. The value may pass the target; the arc stops at full.
GOAL = "clamp([COMPLICATION.GOAL_PROGRESS_VALUE] / [COMPLICATION.GOAL_PROGRESS_TARGET_VALUE], 0, 1)"


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
    # Icon, then label, from the left end: the arc's start on the top half, its end on the bottom.
    icon = 18
    if bottom:
        ix, iy = polar(187, a1 - 3.5)
        circular = f'startAngle="{a1 - 8}" endAngle="{a0}" direction="COUNTER_CLOCKWISE"'
        r_text = 192
    else:
        ix, iy = polar(193, a0 + 3.5)
        circular = f'startAngle="{a0 + 8}" endAngle="{a1}" direction="CLOCKWISE"'
        r_text = 188
    label = f'''                <PartText x="0" y="0" width="450" height="450">
                    <TextCircular centerX="225" centerY="225" width="{2 * r_text}" height="{2 * r_text}" {circular} align="START" ellipsis="TRUE">
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
            supportedTypes="RANGED_VALUE GOAL_PROGRESS SHORT_TEXT EMPTY"
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
            <Complication type="GOAL_PROGRESS">
                <PartDraw x="0" y="0" width="450" height="450">
                    <Arc centerX="225" centerY="225" width="414" height="414" startAngle="{a0}" endAngle="{a1}">
                        <Stroke color="{TRACK}" thickness="7" cap="ROUND" />
                    </Arc>
                    <Arc centerX="225" centerY="225" width="414" height="414" startAngle="{a0}" endAngle="{a0}">
                        <Stroke color="{ACCENT}" thickness="7" cap="ROUND" />
                        <Transform target="endAngle" value="{a0} + {span} * {GOAL}" />
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


def k(n):
    """A size from the 84 px dial layout, scaled and rounded (Watch Face Format wants whole numbers)."""
    return round(n * DK)


def dial_slot(slot_id, name, display, x, y, policy):
    d = DIAL
    c = d / 2
    plate = f'''                <PartDraw x="0" y="0" width="{d}" height="{d}">
                    <Ellipse x="0" y="0" width="{d}" height="{d}">
                        <Fill color="#ff10141c" />
                        <Stroke color="#1fffffff" thickness="1.5" />
                    </Ellipse>
                    <Arc centerX="{f(c)}" centerY="{f(c)}" width="{k(70)}" height="{k(70)}" startAngle="220" endAngle="500">
                        <Stroke color="#17ffffff" thickness="4" cap="ROUND" />
                    </Arc>
                </PartDraw>'''
    icon_big = f'''                <PartImage x="{k(22)}" y="{k(22)}" width="{k(40)}" height="{k(40)}" tintColor="{ACCENT}">
                    <Image resource="[COMPLICATION.MONOCHROMATIC_IMAGE]" />
                </PartImage>'''
    short_text = f'''                <Condition>
                    <Expressions>
                        <Expression name="iconAndText"><![CDATA[[COMPLICATION.MONOCHROMATIC_IMAGE] != null]]></Expression>
                        <Expression name="titleAndText"><![CDATA[[COMPLICATION.TITLE] != null]]></Expression>
                    </Expressions>
                    <Compare expression="iconAndText">
                        <PartImage x="{k(30)}" y="{k(13)}" width="{k(24)}" height="{k(24)}" tintColor="{ACCENT}">
                            <Image resource="[COMPLICATION.MONOCHROMATIC_IMAGE]" />
                        </PartImage>
{text_part(k(4), k(38), k(76), k(30), k(22), "[COMPLICATION.TEXT]", "#fff3f5fa", "SEMI_BOLD", 24)}
                    </Compare>
                    <Compare expression="titleAndText">
{text_part(k(8), k(17), k(68), k(20), k(13), "[COMPLICATION.TITLE]", ACCENT, "SEMI_BOLD", 24)}
{text_part(k(4), k(36), k(76), k(30), k(22), "[COMPLICATION.TEXT]", "#fff3f5fa", "SEMI_BOLD", 24)}
                    </Compare>
                    <Default>
{text_part(k(4), k(26), k(76), k(32), k(24), "[COMPLICATION.TEXT]", "#fff3f5fa", "SEMI_BOLD", 24)}
                    </Default>
                </Condition>'''
    return f'''        <ComplicationSlot
            name="{name}"
            slotId="{slot_id}"
            displayName="@string/{display}"
            isCustomizable="TRUE"
            supportedTypes="SHORT_TEXT RANGED_VALUE GOAL_PROGRESS MONOCHROMATIC_IMAGE SMALL_IMAGE EMPTY"
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
                    <Arc centerX="{f(c)}" centerY="{f(c)}" width="{k(70)}" height="{k(70)}" startAngle="220" endAngle="220">
                        <Stroke color="{ACCENT}" thickness="4" cap="ROUND" />
                        <Transform target="endAngle" value="220 + 280 * {RANGE}" />
                    </Arc>
                </PartDraw>
{short_text}
            </Complication>
            <Complication type="GOAL_PROGRESS">
{plate}
                <PartDraw x="0" y="0" width="{d}" height="{d}">
                    <Arc centerX="{f(c)}" centerY="{f(c)}" width="{k(70)}" height="{k(70)}" startAngle="220" endAngle="220">
                        <Stroke color="{ACCENT}" thickness="4" cap="ROUND" />
                        <Transform target="endAngle" value="220 + 280 * {GOAL}" />
                    </Arc>
                </PartDraw>
{short_text}
            </Complication>
            <Complication type="MONOCHROMATIC_IMAGE">
{plate}
{icon_big}
            </Complication>
            <Complication type="SMALL_IMAGE">
                <PartImage x="{k(6)}" y="{k(6)}" width="{k(72)}" height="{k(72)}">
                    <Image resource="[COMPLICATION.SMALL_IMAGE]" />
                </PartImage>
            </Complication>
            <Complication type="EMPTY" />
        </ComplicationSlot>'''


ring_d = 106 * K * 2
ring_xy = SLOT / 2 - ring_d / 2

head = f'''<WatchFace clipShape="CIRCLE" height="450" width="450">
    <Metadata key="CLOCK_TYPE" value="DIGITAL" />

    <!-- The glucose circle face. Written by wear/watchfacepush/tools/circle/gen_watchface_xml.py:
         change the layout there, see the README.

         The circle is a picture drawn by the wear app (GlucoseCircleComplication). In ambient mode it
         is hidden and a grey ring with the reading as text is shown instead.

         Angles are in degrees, clockwise from 12 o'clock. The edge slots cover the whole face, so
         their arcs can use the face's centre. -->

    <Scene backgroundColor="#ff000000">

        <PartImage x="0" y="0" width="450" height="450">
            <Variant mode="AMBIENT" target="alpha" value="0" />
            <Image resource="background" />
        </PartImage>

        <!-- Ambient ring, at the radius of the ring in the picture -->
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

        <!-- The ambient reading. Invisible while awake, but it gets the taps on the circle. -->
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

        <!-- Time, also in ambient mode -->
        <DigitalClock x="75" y="310" width="300" height="70">
            <TimeText align="CENTER" format="hh:mm" hourFormat="SYNC_TO_DEVICE"
                x="0" y="0" width="300" height="70">
                <Font color="#fff3f5fa" family="SYNC_TO_DEVICE" size="64" slant="NORMAL" weight="NORMAL" />
            </TimeText>
        </DigitalClock>

        <!-- AM or PM on a 12-hour device: TimeText cannot draw it -->
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

        <!-- Date -->
        <PartText x="115" y="377" width="220" height="26">
            <Variant mode="AMBIENT" target="alpha" value="0" />
            <Text align="CENTER" ellipsis="TRUE">
                {font(17, "#ff9aa3b5", "SEMI_BOLD")}
                    <Template>%s %s %s<Parameter expression="[DAY_OF_WEEK_S]" /><Parameter expression="[DAY]" /><Parameter expression="[MONTH_S]" /></Template>
                </Font>
            </Text>
        </PartText>

        <!-- Dials: empty on the left, IOB on the right -->
'''

parts = [head]
for d in DIALS:
    parts.append(dial_slot(*d))
    parts.append("")
parts.append('''        <!-- Edge slots: watch battery, steps, reservoir and rig battery by default -->
''')
for e in EDGES:
    parts.append(edge_slot(*e))
    parts.append("")
parts.append('''    </Scene>
</WatchFace>
''')

with open(sys.argv[1], "w") as out:
    out.write("\n".join(parts))
