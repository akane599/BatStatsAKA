#!/usr/bin/env python3
"""Validate complete translations and Android format arguments without hiding lint errors."""
from collections import Counter
from pathlib import Path
import re
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1] / "app/src/main/res"
FORMAT = re.compile(r"%%|%(?:(\d+)\$)?[-#+ 0,(]*\d*(?:\.\d+)?([a-zA-Z])")


def strings(path):
    root = ET.parse(path).getroot()
    result = {}
    for node in root:
        if node.tag != "string" or node.get("translatable") == "false":
            continue
        name = node.attrib["name"]
        assert name not in result, f"Duplicate key {path}: {name}"
        result[name] = "".join(node.itertext())
    return result


def arguments(value):
    return Counter((match.group(1), match.group(2)) for match in FORMAT.finditer(value))


base = strings(ROOT / "values/strings.xml")
for locale in ("es", "tr"):
    translated = strings(ROOT / f"values-{locale}/strings.xml")
    assert translated.keys() == base.keys(), f"Incomplete or extra keys in {locale}: {translated.keys() ^ base.keys()}"
    for key, value in translated.items():
        assert value.strip(), f"Empty {locale}:{key}"
        assert arguments(value) == arguments(base[key]), f"Format mismatch {locale}:{key}"
        assert value.count(r"\n") == base[key].count(r"\n"), f"Missing line breaks {locale}:{key}"
    print(f"{locale}: {len(translated)} complete keys; format arguments and line breaks match")
for path in ROOT.rglob("*.xml"):
    ET.parse(path)
print("All Android resource XML parses")
