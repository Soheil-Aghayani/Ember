"""Convert the bundled Solar linear icons and circle-flags using Python 3 only.

Usage: python tools/svg_to_vector.py --source-root "D:/Personal/SVG Icon"
Solar: https://github.com/480-Design/Solar-Icon-Set (CC BY 4.0).
Flags: https://github.com/HatScripts/circle-flags (MIT).
Only the Solar icons below are imported. All two-letter flags are needed because
the server's country list is fetched at runtime. Unsupported SVG features fail
explicitly rather than silently producing a different drawing.
"""

import argparse
from pathlib import Path
import re
import xml.etree.ElementTree as ET


ANDROID = "http://schemas.android.com/apk/res/android"
ET.register_namespace("android", ANDROID)
ICONS = (
    "alt-arrow-left", "alt-arrow-right", "global", "check-circle", "share",
    "settings", "magnifier", "power", "sun", "moon", "sun-2",
    "trash-bin-trash", "copy", "code", "logout",
)
CIRCLE = "M0,256 A256,256 0,1 0,512,256 A256,256 0,1 0,0,256 Z"
NUMBER = r"[-+]?(?:\d*\.\d+|\d+\.?\d*)(?:[eE][-+]?\d+)?"


def attrs(**values):
    return {f"{{{ANDROID}}}{key}": str(value) for key, value in values.items()}


def number(value):
    return format(float(value), ".12g")


def path_data(element):
    """SVG and VectorDrawable share path commands, including elliptical arcs."""
    tag = element.tag.split("}")[-1]
    a = element.attrib
    def n(key, default=0):
        return float(a.get(key, default))
    if tag == "path":
        data = a["d"]
        tokens = re.findall(r"[MmLlHhVvCcSsQqTtAaZz]|" + NUMBER, data)
        if re.sub(r"[\s,]", "", data) != "".join(tokens):
            raise ValueError(f"Unsupported path syntax: {data}")
        # Android's PathParser accepts all SVG commands and compact arc syntax.
        return data
    if tag in ("circle", "ellipse"):
        cx, cy = n("cx"), n("cy")
        rx, ry = (n("r"), n("r")) if tag == "circle" else (n("rx"), n("ry"))
        return (f"M{cx-rx},{cy} A{rx},{ry} 0,1 0,{cx+rx},{cy} "
                f"A{rx},{ry} 0,1 0,{cx-rx},{cy} Z")
    if tag == "rect":
        x, y, w, h = n("x"), n("y"), n("width"), n("height")
        rx = min(n("rx", a.get("ry", 0)), w / 2)
        ry = min(n("ry", a.get("rx", 0)), h / 2)
        if not rx or not ry:
            return f"M{x},{y} h{w} v{h} h{-w} Z"
        return (f"M{x+rx},{y} H{x+w-rx} A{rx},{ry} 0,0 1,{x+w},{y+ry} "
                f"V{y+h-ry} A{rx},{ry} 0,0 1,{x+w-rx},{y+h} "
                f"H{x+rx} A{rx},{ry} 0,0 1,{x},{y+h-ry} "
                f"V{y+ry} A{rx},{ry} 0,0 1,{x+rx},{y} Z")
    if tag == "line":
        return f"M{n('x1')},{n('y1')} L{n('x2')},{n('y2')}"
    if tag in ("polygon", "polyline"):
        points = re.findall(NUMBER, a["points"])
        if len(points) < 4 or len(points) % 2:
            raise ValueError("Invalid polygon points")
        return "M" + ",".join(points[:2]) + " L" + " ".join(
            ",".join(points[i:i+2]) for i in range(2, len(points), 2)
        ) + (" Z" if tag == "polygon" else "")
    raise ValueError(f"Unsupported SVG element: {tag}")


def color(value):
    if value == "none":
        return "#00000000"
    if value == "currentColor":
        return "#FF000000"
    if re.fullmatch(r"#[0-9a-fA-F]{3}", value):
        return "#FF" + "".join(c * 2 for c in value[1:])
    if re.fullmatch(r"#[0-9a-fA-F]{6}", value):
        return "#FF" + value[1:]
    raise ValueError(f"Unsupported colour: {value}")


def convert(source, flag=False):
    svg = ET.parse(source).getroot()
    x, y, width, height = map(float, svg.attrib["viewBox"].split())
    if x or y:
        raise ValueError("Nonzero viewBox origin")
    vector = ET.Element("vector", attrs(
        width="24dp", height="24dp", viewportWidth=number(width),
        viewportHeight=number(height),
    ))
    if not flag:
        vector.set(f"{{{ANDROID}}}tint", "?attr/colorControlNormal")
        if source.stem in ("alt-arrow-left-linear", "alt-arrow-right-linear", "logout-linear"):
            vector.set(f"{{{ANDROID}}}autoMirrored", "true")
    ids = {e.attrib["id"]: e for e in svg.iter() if "id" in e.attrib}
    styles = {"fill", "stroke", "stroke-width", "stroke-linecap", "stroke-linejoin",
              "fill-rule", "fill-opacity", "stroke-opacity"}

    def visit(element, parent, inherited, references=()):
        tag = element.tag.split("}")[-1]
        if tag in ("defs", "mask"):
            return
        a = element.attrib
        allowed = styles | {"id", "d", "x", "y", "cx", "cy", "r", "rx", "ry",
                            "width", "height", "viewBox", "mask", "transform", "href",
                            "x1", "y1", "x2", "y2", "points"}
        unknown = set(a) - allowed
        if unknown:
            raise ValueError(f"Unsupported attributes: {unknown}")
        style = inherited | {k: v for k, v in a.items() if k in styles}
        if "transform" in a:
            match = re.fullmatch(r"translate\(\s*(" + NUMBER + r")(?:[\s,]+(" + NUMBER + r"))?\s*\)", a["transform"])
            if not match:
                raise ValueError(f"Unsupported transform: {a['transform']}")
            parent = ET.SubElement(parent, "group", attrs(
                translateX=match[1], translateY=match[2] or "0"))
        if "mask" in a:
            mask = ids[a["mask"].removeprefix("url(#").removesuffix(")")]
            children = list(mask)
            if (not flag or len(children) != 1 or
                    children[0].tag.split("}")[-1] != "circle" or
                    any(children[0].get(k) != "256" for k in ("cx", "cy", "r"))):
                raise ValueError("Only the circle-flags circle mask is supported")
            parent = ET.SubElement(parent, "group")
            ET.SubElement(parent, "clip-path", attrs(pathData=CIRCLE))
        if tag in ("svg", "g"):
            for child in element:
                visit(child, parent, style, references)
        elif tag == "use":
            ref = a["href"].removeprefix("#")
            if ref in references or "x" in a or "y" in a:
                raise ValueError("Recursive or positioned use is unsupported")
            visit(ids[ref], parent, style, references + (ref,))
        else:
            values = {"pathData": path_data(element), "fillColor": color(style.get("fill", "#000000"))}
            if "stroke" in style:
                values["strokeColor"] = color(style["stroke"])
                values["strokeWidth"] = style.get("stroke-width", "1")
            for svg_key, android_key in (
                ("stroke-linecap", "strokeLineCap"), ("stroke-linejoin", "strokeLineJoin"),
                ("fill-opacity", "fillAlpha"), ("stroke-opacity", "strokeAlpha"),
            ):
                if svg_key in style:
                    values[android_key] = style[svg_key]
            if "fill-rule" in style:
                values["fillType"] = {"evenodd": "evenOdd", "nonzero": "nonZero"}[style["fill-rule"]]
            ET.SubElement(parent, "path", attrs(**values))

    visit(svg, vector, {})
    ET.indent(vector, space="    ")
    result = ET.tostring(vector, encoding="unicode") + "\n"
    ET.fromstring(result)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=Path(__file__).resolve().parents[1] / "android/src/main/res/drawable")
    args = parser.parse_args()
    outputs = {}
    for name in ICONS:
        outputs[f"ic_solar_{name.replace('-', '_')}.xml"] = convert(args.source_root / "solar/solar" / f"{name}-linear.svg")
    for source in sorted((args.source_root / "circle-flags/circle-flags").glob("*.svg")):
        if re.fullmatch(r"[a-z]{2}", source.stem):
            outputs[f"flag_{source.stem}.xml"] = convert(source, flag=True)
    if not any(name.startswith("flag_") for name in outputs):
        raise ValueError("No country flags found")
    args.output.mkdir(parents=True, exist_ok=True)
    for name, contents in outputs.items():
        (args.output / name).write_text(contents, encoding="utf-8", newline="\n")
    print(f"Converted and XML-validated {len(ICONS)} icons and {len(outputs)-len(ICONS)} flags")


if __name__ == "__main__":
    main()
