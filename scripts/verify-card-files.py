"""Inspect exported geometry and Blender's own scene report for delivery checks."""
from pathlib import Path
import argparse
import json
import struct

from PIL import Image


def inspect_card(root):
    root = Path(root).resolve()
    with Image.open(root / 'assets' / 'background.png') as background:
        if 'A' in background.getbands() and background.getchannel('A').getextrema() != (255, 255):
            raise ValueError('The background layer must be completely opaque')
    model = root / 'web' / 'assets' / 'card.glb'
    raw = model.read_bytes()
    magic, version, length = struct.unpack_from('<4sII', raw)
    if magic != b'glTF' or version != 2 or length != len(raw):
        raise ValueError('Invalid GLB header or incomplete geometry export')
    chunk_length, chunk_type = struct.unpack_from('<II', raw, 12)
    if chunk_type != 0x4E4F534A:
        raise ValueError('GLB JSON chunk missing')
    geometry = json.loads(raw[20:20 + chunk_length].decode('utf-8'))
    if not geometry.get('meshes') or not geometry.get('accessors'):
        raise ValueError('The card GLB must contain real mesh geometry')
    materials = {material['name'] for material in geometry.get('materials', [])}
    required_materials = {'web_front', 'web_edge', 'web_back', 'web_gold'}
    if not required_materials.issubset(materials):
        raise ValueError('Missing browser material roles: ' + str(sorted(required_materials - materials)))
    scene_report = json.loads((root / 'verification.json').read_text(encoding='utf-8'))
    if not all(image['packed'] for image in scene_report['images'].values()):
        raise ValueError('Editable Blender file must pack all image layers')
    if not all(abs(plane['rotation_degrees'][0] - 90) < 0.01 for plane in scene_report['planes'].values()):
        raise ValueError('Card image planes must retain the X=90 degree object transform')
    report = {
        'card': root.name,
        'geometryBytes': len(raw),
        'meshCount': len(geometry['meshes']),
        'materialRoles': sorted(materials),
        'packedImages': len(scene_report['images']),
        'planeTransformsValid': True,
        'backgroundOpaque': True,
    }
    (root / 'geometry-validation.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('project')
    arguments = parser.parse_args()
    print(json.dumps(inspect_card(arguments.project), indent=2))
