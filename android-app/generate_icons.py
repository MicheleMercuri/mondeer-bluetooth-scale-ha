"""Genera ic_launcher.png e ic_launcher_round.png per ogni density Android
dall'immagine sorgente `app_icon_source.png`. Usato una tantum quando si
aggiorna l'icona dell'app (tipicamente: tagga release nuova).
"""
import os
import sys
from PIL import Image, ImageDraw

SOURCE = "app_icon_source.png"
DENSITIES = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}
RES_DIR = os.path.join("app", "src", "main", "res")


def make_round(im: Image.Image) -> Image.Image:
    """Applica una maschera circolare per ic_launcher_round.png."""
    w, h = im.size
    mask = Image.new("L", (w, h), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, w, h), fill=255)
    out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    out.paste(im, (0, 0), mask)
    return out


def main():
    if not os.path.exists(SOURCE):
        print(f"ERROR: {SOURCE} not found", file=sys.stderr)
        sys.exit(1)
    src = Image.open(SOURCE).convert("RGBA")
    print(f"Source: {src.size}")

    for d, px in DENSITIES.items():
        out_dir = os.path.join(RES_DIR, f"mipmap-{d}")
        os.makedirs(out_dir, exist_ok=True)

        scaled = src.resize((px, px), Image.LANCZOS)
        scaled.save(os.path.join(out_dir, "ic_launcher.png"))
        make_round(scaled).save(os.path.join(out_dir, "ic_launcher_round.png"))
        print(f"  mipmap-{d}: {px}x{px} ic_launcher.png + ic_launcher_round.png")

    # Rimuove gli adaptive icon XML che farebbero override del PNG su
    # Android 8+. Vogliamo che il PNG sia usato ovunque.
    for path in [
        os.path.join(RES_DIR, "mipmap-anydpi-v26", "ic_launcher.xml"),
        os.path.join(RES_DIR, "mipmap-anydpi-v26", "ic_launcher_round.xml"),
        os.path.join(RES_DIR, "drawable", "ic_launcher_foreground.xml"),
        os.path.join(RES_DIR, "drawable", "ic_launcher_background.xml"),
    ]:
        if os.path.exists(path):
            os.remove(path)
            print(f"  removed {path}")

    print("OK")


if __name__ == "__main__":
    main()
