#!/usr/bin/env python3
"""Validate complete translations and Android format arguments without hiding lint errors."""
from collections import Counter
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1] / "app/src/main/res"
FORMAT = re.compile(r"%%|%(?:(\d+)\$)?[-#+ 0,(]*\d*(?:\.\d+)?([a-zA-Z])")


def strings_in_file(path):
    root = ET.parse(path).getroot()
    result = {}
    for node in root:
        if node.tag != "string" or node.get("translatable") == "false":
            continue
        name = node.attrib["name"]
        assert name not in result, f"Duplicate key {path}: {name}"
        result[name] = "".join(node.itertext())
    return result


def strings_for_locale(dir_name):
    """Merge every values*/strings*.xml file for one locale directory into one dict,
    treating them as a single string table (a future strings_now.xml, etc. included)."""
    merged = {}
    origin = {}
    for path in sorted((ROOT / dir_name).glob("strings*.xml")):
        for name, value in strings_in_file(path).items():
            assert name not in merged, f"Duplicate key {name} in {dir_name}: {origin[name]} and {path}"
            merged[name] = value
            origin[name] = path
    return merged


def arguments(value):
    return Counter((match.group(1), match.group(2)) for match in FORMAT.finditer(value))


base = strings_for_locale("values")
for locale in ("es", "tr"):
    translated = strings_for_locale(f"values-{locale}")
    assert translated.keys() == base.keys(), f"Incomplete or extra keys in {locale}: {translated.keys() ^ base.keys()}"
    for key, value in translated.items():
        assert value.strip(), f"Empty {locale}:{key}"
        assert arguments(value) == arguments(base[key]), f"Format mismatch {locale}:{key}"
        assert value.count(r"\n") == base[key].count(r"\n"), f"Missing line breaks {locale}:{key}"
    print(f"{locale}: {len(translated)} complete keys; format arguments and line breaks match")
for path in ROOT.rglob("*.xml"):
    ET.parse(path)
print("All Android resource XML parses")
