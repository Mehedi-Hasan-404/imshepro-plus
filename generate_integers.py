#!/usr/bin/env python3
"""
generate_integers.py
--------------------
Run from the root of the Android project to create/update integers.xml
in every res/values-* folder.

Usage:
    python generate_integers.py

It will auto-detect the res/ directory (searches up to 3 levels deep).
"""

import os
import xml.etree.ElementTree as ET

# ─────────────────────────────────────────────
# EDIT THESE to change any integer value
# ─────────────────────────────────────────────
INTEGERS = {
    # qualifier              grid_column_count   event_span_count
    "values":               {"grid_column_count": 3,  "event_span_count": 1},
    "values-land":          {"grid_column_count": 6,  "event_span_count": 3},
    "values-sw600dp":       {"grid_column_count": 4,  "event_span_count": 3},
    "values-sw600dp-land":  {"grid_column_count": 6,  "event_span_count": 3},
    "values-sw720dp":       {"grid_column_count": 5,  "event_span_count": 3},
    "values-sw720dp-land":  {"grid_column_count": 8,  "event_span_count": 3},
    "values-sw960dp":       {"grid_column_count": 6,  "event_span_count": 3},
    "values-sw960dp-land":  {"grid_column_count": 10, "event_span_count": 3},
    "values-television":    {"grid_column_count": 6,  "event_span_count": 3},
}
# ─────────────────────────────────────────────


def find_res_dir():
    """Walk up to 3 levels to find app/src/main/res."""
    candidates = [
        "app/src/main/res",
        "../app/src/main/res",
        "../../app/src/main/res",
        "src/main/res",
    ]
    for c in candidates:
        if os.path.isdir(c):
            return os.path.normpath(c)
    raise FileNotFoundError(
        "Could not find res/ directory. Run this script from the project root."
    )


def upsert_integers(xml_path: str, integers: dict[str, int]):
    """Create or update integers.xml, preserving any existing entries not in our dict."""
    if os.path.exists(xml_path):
        tree = ET.parse(xml_path)
        root = tree.getroot()
    else:
        root = ET.Element("resources")
        tree = ET.ElementTree(root)

    existing = {el.get("name"): el for el in root.findall("integer")}

    for name, value in integers.items():
        if name in existing:
            existing[name].text = str(value)
        else:
            el = ET.SubElement(root, "integer")
            el.set("name", name)
            el.text = str(value)

    # Pretty-print
    ET.indent(tree, space="    ")
    tree.write(xml_path, encoding="utf-8", xml_declaration=True)
    print(f"  ✔  {xml_path}")


def main():
    res_dir = find_res_dir()
    print(f"Found res/ at: {res_dir}\n")

    for qualifier, integers in INTEGERS.items():
        folder = os.path.join(res_dir, qualifier)
        os.makedirs(folder, exist_ok=True)
        xml_path = os.path.join(folder, "integers.xml")
        upsert_integers(xml_path, integers)

    print("\nDone! All integers.xml files are up to date.")


if __name__ == "__main__":
    main()
