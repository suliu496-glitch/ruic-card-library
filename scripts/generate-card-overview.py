from __future__ import annotations
import argparse
import io
import json
import zipfile
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

def load_font(path: str | None, size: int):
    candidates = [path] if path else []
    candidates += [
        r"C:\Windows\Fonts\msyh.ttc",
        r"C:\Windows\Fonts\simhei.ttf",
        "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc",
        "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
    ]
    for candidate in candidates:
        if candidate and Path(candidate).exists():
            try:
                return ImageFont.truetype(candidate, size)
            except OSError:
                pass
    return ImageFont.load_default()

def main():
    parser = argparse.ArgumentParser(description="Build a labeled contact sheet from RuiC .ruic covers.")
    parser.add_argument("--cards", type=Path, default=Path("flashcards"))
    parser.add_argument("--output", type=Path, default=Path("previews/all-cards-001-039.jpg"))
    parser.add_argument("--font", default=None)
    args = parser.parse_args()
    files = sorted(args.cards.glob("*.ruic"))
    catalog = json.loads((args.cards / "catalog-001-039.json").read_text(encoding="utf-8-sig"))
    if len(files) != 39 or catalog.get("totalCards") != 39:
        raise SystemExit(f"Expected 39 .ruic cards and catalog entries; found {len(files)}")
    records = {record["file"]: record for record in catalog["cards"]}
    columns, thumb_w, thumb_h = 5, 210, 315
    gap_x, gap_y, label_h = 18, 18, 42
    margin, header_h = 28, 96
    rows = (len(files) + columns - 1) // columns
    width = margin * 2 + columns * thumb_w + (columns - 1) * gap_x
    height = header_h + margin + rows * (thumb_h + label_h) + (rows - 1) * gap_y + margin
    sheet = Image.new("RGB", (width, height), "#f3f6fa")
    draw = ImageDraw.Draw(sheet)
    title_font = load_font(args.font, 30)
    subtitle_font = load_font(args.font, 16)
    label_font = load_font(args.font, 17)
    draw.text((margin, 18), "映光卡匣 · RuiC 闪卡 001–039", font=title_font, fill="#182333")
    draw.text((margin, 60), "39 张真分层卡｜AI 漫剧人物 · 风景壁纸 · 其他插画与动物", font=subtitle_font, fill="#617087")
    for index, card_file in enumerate(files):
        record = records.get(card_file.name, {})
        with zipfile.ZipFile(card_file) as archive:
            cover = Image.open(io.BytesIO(archive.read("cover.png"))).convert("RGB")
        cover.thumbnail((thumb_w, thumb_h), Image.Resampling.LANCZOS)
        x = margin + (index % columns) * (thumb_w + gap_x)
        y = header_h + margin + (index // columns) * (thumb_h + label_h + gap_y)
        tile = Image.new("RGB", (thumb_w, thumb_h), "white")
        tile.paste(cover, ((thumb_w - cover.width) // 2, (thumb_h - cover.height) // 2))
        sheet.paste(tile, (x, y))
        sku = f"{index + 1:03d}"
        name = record.get("title", card_file.stem)
        draw.text((x + 2, y + thumb_h + 8), f"{sku}  {name}", font=label_font, fill="#243348")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    sheet.save(args.output, format="JPEG", quality=91, optimize=True)
    print(json.dumps({"cards": len(files), "size": [width, height], "output": str(args.output)}))

if __name__ == "__main__":
    main()