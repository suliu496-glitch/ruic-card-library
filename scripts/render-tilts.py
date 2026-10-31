"""Render canonical front and both tilt directions without changing card.blend."""
from pathlib import Path
import json
import math
import sys

import bpy


args = sys.argv[sys.argv.index('--') + 1:]
root = Path(args[0]).resolve()
scene = bpy.context.scene
# The skill animates a common card pivot; render controlled poses so parallax
# direction and layer clipping can be inspected independently of the animation.
pivot = next((obj for obj in scene.objects if '主体深度' in obj), None)
if pivot is None:
    raise RuntimeError('RuiC card pivot with editable depth properties was not found')
pivot.animation_data_clear()
scene.render.resolution_percentage = 70
scene.cycles.samples = 24
poses = [('front', 0, 0), ('tilt-left', -16, -8), ('tilt-right', 16, 8)]
report = []
for name, horizontal, vertical in poses:
    pivot.rotation_euler = (math.radians(vertical), 0, math.radians(horizontal))
    scene.render.filepath = str(root / 'renders' / (name + '.png'))
    bpy.context.view_layer.update()
    bpy.ops.render.render(write_still=True)
    report.append({'name': name, 'rotationDegrees': [vertical, 0, horizontal], 'image': name + '.png'})
(root / 'renders' / 'tilt-report.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
print('FRONT_AND_BOTH_TILT_RENDERS_COMPLETE')
