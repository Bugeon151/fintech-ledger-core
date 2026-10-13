"""Report the test run as GitHub check annotations: a per-class summary, plus compile errors and failures."""
import glob
import re
import sys
import xml.etree.ElementTree as ET

MAX_INDIVIDUAL = 8
emitted = 0


def esc(text):
    return text.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")


def emit(title, message, file=None, line=None):
    global emitted
    location = f"file={file},line={line}," if file else ""
    print(f"::error {location}title={esc(title)}::{esc(message[:12000])}")
    emitted += 1


def summarize(trace):
    lines = trace.splitlines()
    causes = [l.strip() for l in lines if l.strip().startswith("Caused by:")]
    return "\n".join(lines[:25] + ["..."] + causes[-6:])


log_lines = open(sys.argv[1], errors="replace").read().splitlines()
results_dir = sys.argv[2]

for i, line in enumerate(log_lines):
    match = re.search(r"(src/\S+\.java):(\d+): error: (.*)", line)
    if match and emitted < MAX_INDIVIDUAL:
        emit("compile error", match.group(3) + "\n" + "\n".join(log_lines[i + 1:i + 3]), match.group(1), match.group(2))

failures = []
for path in sorted(glob.glob(f"{results_dir}/*.xml")):
    for case in ET.parse(path).getroot().iter("testcase"):
        for problem in list(case.iter("failure")) + list(case.iter("error")):
            name = f"{case.get('classname')}.{case.get('name')}"
            failures.append(name)
            if emitted < MAX_INDIVIDUAL:
                emit(name, summarize((problem.get("message") or "") + "\n" + (problem.text or "")))

summary, totals = [], [0, 0, 0, 0]
for path in sorted(glob.glob(f"{results_dir}/*.xml")):
    suite = ET.parse(path).getroot()
    counts = [int(suite.get(k, 0)) for k in ("tests", "failures", "errors", "skipped")]
    totals = [a + b for a, b in zip(totals, counts)]
    summary.append(f"{suite.get('name').rsplit('.', 1)[-1]}: tests={counts[0]} failed={counts[1] + counts[2]} "
                   f"skipped={counts[3]} time={suite.get('time')}s")
print(f"::notice title={esc(f'tests={totals[0]} failed={totals[1] + totals[2]} skipped={totals[3]}')}::"
      f"{esc(chr(10).join(summary))}")

if failures or emitted:
    tail = "\n".join(log_lines[-80:])
    emit(f"{len(failures)} failing test(s)", "\n".join(failures[:60]) + "\n\n--- build log tail ---\n" + tail)
