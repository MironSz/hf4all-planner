"""Extract the original HF4A card images from the official Vassal module.

The card artwork is (c) ION Game Design / Sierra Madre Games and is NOT stored in
git (see ../.gitignore). This script recreates assets/cards/ locally:

    python tools/extract_cards.py --download          # fetch the module, verify, extract
    python tools/extract_cards.py path/to/High_frontier4_1.6.1.vmod

The module ("High Frontier 4 All" by Stefano Tine', released with permission from
ION/SMG) comes from the Vassal module library. The archive is only read as data:
nothing inside it is executed.

Output: assets/cards/<id>-w.jpg, <id>-b.jpg for every patent card in
data/cards.js, and crew-<crewId>-<face>.jpg for the Crew cards; assets/gfx/ gets
the other game graphics the UI uses (playmat, Fuel Strip, Dry/Wet chits, map
pieces, beads, dice, glory and heroism chits, faction logos - see GFX below).
"""
import hashlib
import json
import os
import re
import sys
import urllib.request
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
GAME = os.path.dirname(HERE)
OUT = os.path.join(GAME, 'assets', 'cards')
CARDS_JS = os.path.join(GAME, 'data', 'cards.js')

MODULE_URL = 'https://obj.vassalengine.org/gls/7/4/High_frontier4_1.6.1.vmod'
MODULE_SHA256 = '74e9a99708e1bfc0fb24dfe4e299d2c9aa570604938f7cca9847b888879bb41d'
CORE_DECKS = ('Thrusters Deck', 'Robonauts Deck', 'Refineries Deck', 'Reactors Deck', 'Radiators Deck', 'Generators Deck')
GFX_OUT = os.path.join(GAME, 'assets', 'gfx')
COLOURS = ('white', 'red', 'green', 'purple', 'yellow', 'grey')
ZONES = ('Mercury', 'Venus', 'Earth', 'Mars', 'Ceres', 'Jupiter', 'Saturn', 'Uranus', 'Neptune')
LOGOS = ('NASA', 'ISRO', 'UN', 'B612', 'CNSA', 'ROSCOSMOS', 'ESA', 'P2P', 'Shimizu', 'NASRDA', 'SpaceX', 'NORSE')


def gfx_files():
    """Module image name -> file name in assets/gfx/ (UI graphics other than the cards)."""
    files = ['fuel_strip.png', 'LEO_outposts_freighter.png', 'Rocket_stack.png', 'hand.png',
             'Wet_blue.png', 'Wet_black.png', 'Wet_gold.png', 'aqua-bead.png', 'isotope-bead.png',
             'firstplayer.png', 'star-gold.png', 'disk-busted.svg', 'icon-playmat.png']
    for c in COLOURS:
        files += ['Dry_%s.png' % c, 'rocket-%s.png' % c, 'outpost1_%s.png' % c, 'outpost2_%s.png' % c,
                  'claim-%s.png' % c, 'factory-%s.png' % c, 'colony-%s.png' % c]
    files += ['die%d.png' % v for v in range(1, 7)]
    files += ['Glory-%s.png' % z for z in ZONES] + ['Glory-%dVP.png' % v for v in range(2, 7)]
    files += ['heroism%d.png' % v for v in range(1, 5)]
    files += ['logo-%s.png' % n for n in LOGOS]
    out = {f: f for f in files}
    out['HF4-Sol Sunspot Cycle (core).jpg'] = 'sunspot-cycle.jpg'
    return out


def load_cards():
    src = open(CARDS_JS, encoding='utf-8').read()
    return json.loads(src[src.index('= {') + 2: src.rindex(';')])


def download(dest_dir):
    os.makedirs(dest_dir, exist_ok=True)
    path = os.path.join(dest_dir, os.path.basename(MODULE_URL))
    if not os.path.exists(path):
        print('downloading', MODULE_URL)
        urllib.request.urlretrieve(MODULE_URL, path)
    digest = hashlib.sha256(open(path, 'rb').read()).hexdigest()
    if digest != MODULE_SHA256:
        sys.exit('SHA-256 mismatch for %s: %s' % (path, digest))
    return path


def parse_build_file(xml):
    """entryName -> (white image, black image) for core patent decks; crew colour -> (face0, face1)."""
    piles = [(m.start(), re.search(r' name="([^"]*)"', m.group(0)).group(1))
             for m in re.finditer(r'<VASSAL\.build\.module\.map\.DrawPile [^>]*>', xml)]
    patents = {}
    for m in re.finditer(r'<VASSAL\.build\.widget\.CardSlot entryName="([^"]*)"[^>]*>(.*?)</VASSAL\.build\.widget\.CardSlot>', xml, re.S):
        before = [n for p, n in piles if p < m.start()]
        if not before or before[-1] not in CORE_DECKS:
            continue
        body = m.group(2)
        black = re.search(r'obs;[^;]*;(Card-\d+\.jpg);', body)
        white = re.search(r'piece;;;(Card-\d+\.jpg);', body)
        if black and white:
            patents[m.group(1)] = (white.group(1), black.group(1))
    crews = {}
    for m in re.finditer(r'PieceSlot entryName="(\w+)"[^>]*>\+/null/prototype;CrewCard.*?emb2;.*?;(Card-\d+\.jpg),(Card-\d+\.jpg);', xml, re.S):
        crews[m.group(1)] = (m.group(2), m.group(3))
    return patents, crews


def main(argv):
    if not argv or argv[0] in ('-h', '--help'):
        print(__doc__)
        return
    vmod = download(os.path.join(GAME, '.cache')) if argv[0] == '--download' else argv[0]
    data = load_cards()
    z = zipfile.ZipFile(vmod)
    xml = z.read('buildFile.xml').decode('utf-8', errors='replace')
    patents, crews = parse_build_file(xml)
    os.makedirs(OUT, exist_ok=True)
    written, missing = 0, []

    def save(member, name):
        nonlocal written
        with open(os.path.join(OUT, name), 'wb') as f:
            f.write(z.read('images/' + member))
        written += 1

    for deck in data['decks'].values():
        for card in deck:
            imgs = patents.get(card['vassal'])
            if not imgs:
                missing.append(card['id'] + ' ' + card['vassal'])
                continue
            save(imgs[0], card['id'] + '-w.jpg')
            save(imgs[1], card['id'] + '-b.jpg')
    for crew in data['crews']:
        imgs = crews.get(crew['vassal'])
        if not imgs:
            missing.append(crew['id'] + ' ' + crew['vassal'])
            continue
        save(imgs[0], 'crew-%s-0.jpg' % crew['id'])
        save(imgs[1], 'crew-%s-1.jpg' % crew['id'])
    print('wrote %d images to %s' % (written, OUT))
    os.makedirs(GFX_OUT, exist_ok=True)
    gfx = 0
    for member, name in gfx_files().items():
        try:
            blob = z.read('images/' + member)
        except KeyError:
            missing.append('graphic ' + member)
            continue
        with open(os.path.join(GFX_OUT, name), 'wb') as f:
            f.write(blob)
        gfx += 1
    print('wrote %d graphics to %s' % (gfx, GFX_OUT))
    if missing:
        print('NOT FOUND in the module:', ', '.join(missing))
        sys.exit(1)


if __name__ == '__main__':
    main(sys.argv[1:])
