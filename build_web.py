"""Builds the app page for Android (offline fonts) and for the web preview (Google Fonts)."""
import pathlib
root = pathlib.Path(__file__).parent
app = (root / "web/app.html").read_text()
faces = "".join(
    f'@font-face{{font-family:"{fam}";font-weight:{w};font-style:normal;font-display:swap;src:url("fonts/{file}") format("woff2")}}\n'
    for fam, w, file in [("Figtree",400,"figtree-latin-400-normal.woff2"),("Figtree",500,"figtree-latin-500-normal.woff2"),
                         ("Figtree",600,"figtree-latin-600-normal.woff2"),("Figtree",700,"figtree-latin-700-normal.woff2"),
                         ("JetBrains Mono",400,"jetbrains-mono-latin-400-normal.woff2"),("JetBrains Mono",500,"jetbrains-mono-latin-500-normal.woff2")])
android = ('<!doctype html><html lang="en"><head><meta charset="utf-8">'
           '<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">'
           '<style>[hidden]{display:none!important}body{margin:0}</style>'
           + app.replace("<!--FONTS-->", f"<style>{faces}</style>") + "</html>")
(root / "android/app/src/main/assets/index.html").write_text(android)
gf = ('<link rel="preconnect" href="https://fonts.googleapis.com"><link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>'
      '<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Figtree:wght@400;500;600;700&family=JetBrains+Mono:wght@400;500&display=swap">')
(root / "preview").mkdir(exist_ok=True)
(root / "preview/risk-ledger.html").write_text(app.replace("<!--FONTS-->", gf))
print("ok")
