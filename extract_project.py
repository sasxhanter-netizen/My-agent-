from pathlib import Path
import re
import sys

workflow = Path(".github/workflows/build.yml")
text = workflow.read_text()

pattern = re.compile(
    r"^\s{10}cat (>>|>) ([^\s]+) <<'EOF'\s*\n(.*?)^\s{10}EOF\s*$",
    re.MULTILINE | re.DOTALL
)

blocks = pattern.findall(text)

if not blocks:
    sys.exit("خطأ: لم يتم العثور على كتل الكود.")

files = {}

for mode, filename, content in blocks:
    path = Path(filename)

    if path.is_absolute() or ".." in path.parts:
        sys.exit(f"مسار غير آمن: {filename}")

    if filename not in files:
        if mode == ">>":
            sys.exit(f"خطأ: يوجد إلحاق قبل إنشاء الملف: {filename}")
        files[filename] = content
    else:
        if mode == ">>":
            files[filename] += content
        else:
            sys.exit(f"خطأ: محاولة إنشاء الملف مرتين: {filename}")

existing = [name for name in files if Path(name).exists()]
if existing:
    sys.exit("توقفت دون تعديل. هذه الملفات موجودة بالفعل:\n" + "\n".join(existing))

for filename, content in files.items():
    path = Path(filename)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content)

print(f"\nتم استخراج {len(files)} ملفاً بنجاح:")
for filename in files:
    print(f"{filename} — {Path(filename).stat().st_size} بايت")
