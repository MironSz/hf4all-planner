"""Copy the original Fuel Strip (cut out of the HF4A rulebook) and its chit positions from the planner.

The mission planner already has the strip image (src/main/resources/static/docs/fuel-strip-transparent.png)
and, edited with its /chit-editor, the position of every one of the 57 black-line steps on it
(src/main/resources/static/data/chits-edited.json; step 0 = Wet Mass 1, step 56 = Wet Mass 32).
This script reads both (read-only) and writes the game's copies:

    assets/fuel-strip.png      the strip image
    data/fuelstrip.js          window.HF_FUEL_STRIP = {img, w, h, steps: [[x, y], ...]}  (x, y in 0..1)

    python tools/build_fuelstrip.py
"""
import json
import os
import shutil
import struct

HERE = os.path.dirname(os.path.abspath(__file__))
GAME = os.path.dirname(HERE)
REPO = os.path.dirname(GAME)
STATIC = os.path.join(REPO, 'src', 'main', 'resources', 'static')
SRC_IMG = os.path.join(STATIC, 'docs', 'fuel-strip-transparent.png')
SRC_CHITS = [os.path.join(STATIC, 'data', 'chits-edited.json'), os.path.join(STATIC, 'data', 'chits.json')]
OUT_IMG = os.path.join(GAME, 'assets', 'fuel-strip.png')
OUT_JS = os.path.join(GAME, 'data', 'fuelstrip.js')


def png_size(path):
    with open(path, 'rb') as f:
        head = f.read(24)
    if head[:8] != b'\x89PNG\r\n\x1a\n':
        raise SystemExit('not a PNG: ' + path)
    return struct.unpack('>II', head[16:24])


def main():
    src = next((p for p in SRC_CHITS if os.path.isfile(p)), None)
    if not src:
        raise SystemExit('no chit positions found in the planner (' + ', '.join(SRC_CHITS) + ')')
    chits = json.load(open(src, encoding='utf-8'))
    chits = chits['chits'] if isinstance(chits, dict) else chits
    by_step = {int(c['step']): (round(c['x'], 6), round(c['y'], 6)) for c in chits}
    missing = [s for s in range(57) if s not in by_step]
    if missing:
        raise SystemExit('steps without a position: ' + str(missing))
    w, h = png_size(SRC_IMG)
    os.makedirs(os.path.dirname(OUT_IMG), exist_ok=True)
    shutil.copyfile(SRC_IMG, OUT_IMG)
    payload = {'source': 'planner static/docs/fuel-strip-transparent.png + ' + os.path.basename(src),
               'img': 'assets/fuel-strip.png', 'w': w, 'h': h,
               'steps': [list(by_step[s]) for s in range(57)]}
    tmp = OUT_JS + '.tmp'
    with open(tmp, 'w', encoding='utf-8', newline='\n') as f:
        f.write('/* GENERATED FILE - do not edit by hand. Regenerate with tools/build_fuelstrip.py */\n')
        f.write('window.HF_FUEL_STRIP = ' + json.dumps(payload, separators=(',', ':')) + ';\n')
    os.replace(tmp, OUT_JS)
    print('wrote', OUT_IMG, '(%dx%d)' % (w, h), 'and', OUT_JS, '57 steps from', os.path.basename(src))


if __name__ == '__main__':
    main()
