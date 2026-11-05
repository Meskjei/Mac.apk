"""Check a real runtime log after opening a SurfaceView page and its detail page."""
import re
import sys
from pathlib import Path

log = Path(sys.argv[1]).read_text(errors="replace")
windows = re.findall(
    r"eglCreateWindowSurface\(win=(0x[0-9a-f]+) -> CAMetalLayer=(0x[0-9a-f]+)\) -> EGLSurface=(0x[0-9a-f]+)",
    log,
)
assert len(windows) >= 2, "the recording must open at least two native pages"
assert all(int(surface, 16) for _, _, surface in windows), "a native page failed to create its EGL surface"
assert len({window for window, _, _ in windows}) >= 2, "distinct pages shared an ANativeWindow"
assert len({layer for _, layer, _ in windows}) >= 2, "distinct pages shared a CAMetalLayer"
assert "Failed to swap buffers" not in log, "a native page could not present its frame"
assert "Could not create an EGL window surface" not in log, "a native page lost its EGL producer"
states = re.findall(r"per-surface layer pull \(key (\d+)\): (shown|hidden)", log)
assert any(
    state == "hidden" and (key, "shown") in states[:index] and (key, "shown") in states[index + 1:]
    for index, (key, state) in enumerate(states)
), "the recording must hide the covered page and restore it on Back"
print(f"Native SurfaceView windows: {len(windows)} successful creations, distinct windows/layers, no failed presents")
