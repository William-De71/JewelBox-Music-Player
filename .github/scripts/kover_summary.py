#!/usr/bin/env python3
"""Turns a Kover XML report into a small markdown summary.

Usage: kover_summary.py <report.xml>
Prints markdown on stdout: total line coverage plus a per-class table.
The report only contains the classes selected in build.gradle.kts (the
app logic), so the table stays short.
"""
import sys
import xml.etree.ElementTree as ET


def line_counter(node):
    """Returns (covered, missed) from a node's LINE counter, or None."""
    for counter in node.findall("counter"):
        if counter.get("type") == "LINE":
            return int(counter.get("covered")), int(counter.get("missed"))
    return None


def pct(covered, missed):
    total = covered + missed
    return 100.0 * covered / total if total else 100.0


def main(path):
    root = ET.parse(path).getroot()

    # Aggregate nested/companion classes (Foo$Companion, Foo$State…) into their
    # outer class: serialization companions would otherwise flood the table.
    rows = {}
    for package in root.findall("package"):
        for cls in package.findall("class"):
            counted = line_counter(cls)
            if counted is None:
                continue
            # Kover names classes a/b/Outer$Inner; keep the readable outer name.
            name = cls.get("name").split("/")[-1].split("$")[0]
            covered, missed = rows.get(name, (0, 0))
            rows[name] = (covered + counted[0], missed + counted[1])

    total = line_counter(root) or (0, 0)
    total_pct = pct(*total)
    gate = "✅" if total_pct >= 95 else "❌"

    def table(entries):
        lines = ["| Classe | Lignes | % |", "|---|---:|---:|"]
        for name, (covered, missed) in entries:
            lines.append(f"| `{name}` | {covered}/{covered + missed} | {pct(covered, missed):.1f}% |")
        return "\n".join(lines)

    by_pct = sorted(rows.items(), key=lambda r: pct(*r[1]))
    incomplete = [(name, counts) for name, counts in by_pct if counts[1] > 0]

    # Compact by default: the total, then only what needs attention; the full
    # per-class table stays one click away in a collapsed block.
    print(f"### 📊 Couverture des lignes (logique) : **{total_pct:.1f}%** {gate} <sub>(gate : 95 %)</sub>")
    print()
    if incomplete:
        print(f"{total[1]} ligne(s) manquée(s) dans {len(incomplete)} classe(s) :")
        print()
        print(table(incomplete))
    else:
        print("Toutes les classes mesurées sont à 100 %. 🎉")
    print()
    print("<details>")
    print(f"<summary>Détail des {len(rows)} classes mesurées</summary>")
    print()
    print(table(by_pct))
    print()
    print("</details>")


if __name__ == "__main__":
    main(sys.argv[1])
