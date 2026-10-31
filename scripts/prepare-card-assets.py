"""Derive registered contour/typography layers; leave imagegen artwork untouched."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont, ImageFilter, ImageOps, ImageChops
import json
import argparse

ROOT = Path(__file__).resolve().parents[1]
FONT = Path('C:/Windows/Fonts/simkai.ttf')
SANS = Path('C:/Windows/Fonts/msyh.ttc')
CARDS = [
    ('windowlight', '窗畔相望', '窗边的光，落在你眼里。', '日光与花影', '#d5c6a3'),
    ('dusksea', '暮海来信', '把未说出口的话，交给晚风。', '日落与海风', '#dfb67c'),
    ('bluehour', '蓝调心事', '一个微笑，让平常的瞬间发光。', '蓝调与微光', '#a9cad6'),
    ('sunsettrain', '落日列车', '让晚霞陪你，慢慢驶向远方。', '晚霞与旅途', '#eeb395'),
    ('citywatch', '守望之城', '万家灯火，照亮归途。', '城市与日落', '#df9d68'),
    ('piratecat', '喵海船长', '带上勇气，向着夕阳出航。', '帆影与冒险', '#d8b26b'),
    ('rainkitten', '蘑菇雨伞', '雨再大，也有小小的温柔。', '雨声与小憩', '#a8b894'),
    ('silvermeadow', '银发晴野', '把晴天和微笑，一起收藏。', '晴野与微笑', '#c4ceb1'),
]

def font(path, size):
    return ImageFont.truetype(str(path), size)

def prepare(only=None):
    catalog = []
    for number, (card_id, title, description, subtitle, accent) in enumerate(CARDS, 1):
        edition = f'{number:03d} / {len(CARDS):03d}'
        catalog.append({'id': card_id, 'title': title, 'subtitle': subtitle,
                        'description': description, 'edition': edition,
                        'cover': f'./{card_id}/beautified.png',
                        'configUrl': f'./{card_id}/web/card-config.json',
                        'kind': 'layered', 'source': 'builtin', 'accent': accent})
        if only and card_id not in only:
            continue
        root = ROOT / 'cards' / card_id
        assets = root / 'assets'
        subject = Image.open(assets / 'subject.png').convert('RGBA')
        bg = Image.open(assets / 'background.png')
        if subject.size != (1024, 1536) or bg.size != subject.size:
            raise ValueError(f'Unregistered canvas: {card_id}')
        alpha = subject.getchannel('A')
        # The finished subject itself is the sole contour source. Alpha stays unchanged.
        visible = Image.new('RGB', subject.size, 'white')
        visible.paste(subject.convert('RGB'), mask=alpha)
        edges = visible.convert('L').filter(ImageFilter.FIND_EDGES)
        ink = edges.point(lambda value: 255 if value > 56 else 0)
        ink = ImageChops.multiply(ink, alpha.point(lambda value: 255 if value > 200 else 0))
        ImageOps.invert(ink).convert('RGB').save(assets / 'lineart.png')

        text = Image.new('RGBA', subject.size, (0, 0, 0, 0))
        draw = ImageDraw.Draw(text)
        # Card furniture is fixed to the card surface and kept clear of the face.
        draw.rounded_rectangle((28, 28, 996, 1508), radius=25, outline=(226, 205, 157, 205), width=2)
        for y in range(1230, 1508):
            opacity = round(205 * min(1, (y - 1230) / 145))
            draw.line((30, y, 994, y), fill=(10, 20, 24, opacity))
        draw.line((68, 1300, 956, 1300), fill=(226, 205, 157, 190), width=1)
        draw.text((68, 1319), subtitle, font=font(SANS, 23), fill=(228, 215, 182, 255))
        draw.text((63, 1356), title, font=font(FONT, 74), fill=(250, 243, 223, 255))
        draw.text((70, 1458), f'光影拾藏  /  {number:03d}', font=font(SANS, 20), fill=(228, 215, 182, 255))
        draw.text((954, 1458), '映光卡匣', anchor='ra', font=font(SANS, 20), fill=(228, 215, 182, 255))
        text.save(assets / 'text.png')

        config = {
            'title': title, 'subtitle': subtitle, 'technique': title,
            'tagline': description, 'description': description,
            'edition': edition, 'collection': '光影拾藏',
            'assets': {name: f'./assets/{name}.png' for name in ['subject', 'background', 'text', 'lineart']},
            'parameters': {'subjectScale': 1.05, 'subjectDepth': 0.28, 'backgroundDepth': -0.18,
                           'effectsScale': 1, 'effectsDepth': 0.45, 'foil': 0.35},
            'safeArea': {'scale': 1, 'offset': [0, 0]},
            'font': str(FONT),
        }
        config['assets']['model'] = './assets/card.glb'
        (root / 'card-config.json').write_text(json.dumps(config, ensure_ascii=False, indent=2), encoding='utf-8')
    (ROOT / 'cards' / 'catalog.json').write_text(json.dumps(catalog, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'prepared': [item['id'] for item in catalog]}, ensure_ascii=False))

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--ids', nargs='+', choices=[card[0] for card in CARDS])
    prepare(parser.parse_args().ids)
