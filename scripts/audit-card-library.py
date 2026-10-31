"""Read-only audit of built cards and a SHA256 snapshot of shipped resources."""
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import sys

from PIL import Image


root = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else Path(__file__).resolve().parents[1]
cards = root / 'cards'
catalog = json.loads((cards / 'catalog.json').read_text(encoding='utf-8-sig'))
records = []
errors = []
runtime = {}


def require(condition, message):
    if not condition:
        errors.append(message)


def record_file(path):
    require(path.is_file() and path.stat().st_size > 0, f'Missing or empty: {path}')
    if path.is_file():
        runtime[str(path.relative_to(root)).replace('\\', '/')] = hashlib.sha256(path.read_bytes()).hexdigest()


require(len(catalog) == len({item['id'] for item in catalog}), 'Duplicate catalog IDs')
record_file(cards / 'catalog.json')
for index, item in enumerate(catalog, 1):
    card = cards / item['id']
    config_path = (cards / item['configUrl']).resolve()
    require(config_path.is_relative_to(card), f'{item["id"]}: config escapes card directory')
    record_file(config_path)
    record_file((cards / item['cover']).resolve())
    config = json.loads(config_path.read_text(encoding='utf-8-sig'))
    source = json.loads((card / 'card-config.json').read_text(encoding='utf-8-sig'))
    edition = f'{index:03d} / {len(catalog):03d}'
    require(item['edition'] == config['edition'] == source['edition'] == edition,
            f'{item["id"]}: edition mismatch')
    require(config['title'] == item['title'], f'{item["id"]}: title mismatch')
    require(config['parameters'] == source['parameters'], f'{item["id"]}: source/web parameters differ')
    alpha = {}
    for role in ['model', 'subject', 'background', 'text', 'lineart']:
        path = (config_path.parent / config['assets'][role]).resolve()
        require(path.is_relative_to(card), f'{item["id"]}: {role} escapes card directory')
        record_file(path)
        if role != 'model':
            source_path = (card / source['assets'][role]).resolve()
            require(source_path.is_file() and hashlib.sha256(source_path.read_bytes()).digest()
                    == hashlib.sha256(path.read_bytes()).digest(),
                    f'{item["id"]}: source/web {role} bytes differ')
            with Image.open(path) as layer:
                require(layer.size == (1024, 1536), f'{item["id"]}: wrong {role} canvas')
                if role in ['subject', 'text']:
                    extrema = layer.getchannel('A').getextrema() if 'A' in layer.getbands() else (255, 255)
                    require(extrema[0] < 255 and extrema[1] > 128,
                            f'{item["id"]}: {role} must contain visible and transparent pixels')
                    alpha[role] = extrema
                if role == 'background' and 'A' in layer.getbands():
                    require(layer.getchannel('A').getextrema() == (255, 255),
                            f'{item["id"]}: background is not opaque')
    required = ['card.blend', 'blender-postprocess.json', 'geometry-validation.json',
                'asset-validation.json', 'verification.json', 'renders/hero.png',
                'renders/front.png', 'renders/tilt-left.png', 'renders/tilt-right.png']
    for name in required:
        require((card / name).is_file() and (card / name).stat().st_size > 0,
                f'{item["id"]}: missing artifact {name}')
    geometry = json.loads((card / 'geometry-validation.json').read_text(encoding='utf-8-sig'))
    require(geometry['meshCount'] > 0 and geometry['packedImages'] == 4
            and geometry['planeTransformsValid'] and geometry['backgroundOpaque'],
            f'{item["id"]}: geometry or packed layer contract failed')
    report = json.loads((card / 'verification' / 'report.json').read_text(encoding='utf-8-sig'))
    require(report['summary']['failed'] == 0, f'{item["id"]}: browser checks failed')
    postprocess = json.loads((card / 'blender-postprocess.json').read_text(encoding='utf-8-sig'))
    require(postprocess['foil'] == config['parameters']['foil'] == .35,
            f'{item["id"]}: foil differs between renderers')
    records.append({'id': item['id'], 'title': item['title'], 'edition': edition,
                    'browser': report['summary'], 'geometry': geometry,
                    'alphaExtrema': alpha, 'foil': postprocess['foil']})

result = {'auditedUtc': datetime.now(timezone.utc).isoformat(), 'cardCount': len(records),
          'cards': records, 'browserChecks': {
              key: sum(card['browser'][key] for card in records) for key in ['total', 'passed', 'failed']},
          'errors': errors, 'runtimeSha256': runtime,
          'scope': 'Current config, layers, cover, GLB, packed-image reports and render artifacts; prior browser checks are recorded, not rerun here.'}
output = root / 'verification' / 'card-library-audit.json'
output.write_text(json.dumps(result, indent=2, ensure_ascii=False), encoding='utf-8')
print(json.dumps({'cardCount': len(records), 'browserChecks': result['browserChecks'],
                  'runtimeFilesHashed': len(runtime), 'errors': errors, 'report': str(output)}, ensure_ascii=False))
sys.exit(bool(errors))
