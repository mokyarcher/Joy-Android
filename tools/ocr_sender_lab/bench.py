#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
群 Bot 发送人 OCR 批跑：截图 → 文字框 → 拼「对方·昵称」→ 找 @Bot → 和 labels 对账。

用法（在 apps/joy 下）:
  python tools/ocr_sender_lab/bench.py
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, List, Optional, Tuple

ROOT = Path(__file__).resolve().parent


@dataclass
class Line:
    text: str
    left: float
    top: float
    right: float
    bottom: float

    @property
    def cx(self) -> float:
        return (self.left + self.right) / 2

    @property
    def cy(self) -> float:
        return (self.top + self.bottom) / 2

    @property
    def height(self) -> float:
        return max(self.bottom - self.top, 0.008)


# ---------- OCR engines ----------

def ocr_rapid(path: Path) -> List[Line]:
    from rapidocr_onnxruntime import RapidOCR
    from PIL import Image

    engine = RapidOCR()
    im = Image.open(path).convert("RGB")
    w, h = im.size
    result, _ = engine(str(path))
    lines: List[Line] = []
    if not result:
        return lines
    for item in result:
        # item: [box, text, score]  box = 4 points
        box, text, _score = item[0], item[1], item[2]
        xs = [p[0] for p in box]
        ys = [p[1] for p in box]
        lines.append(
            Line(
                text=str(text).strip(),
                left=min(xs) / w,
                top=min(ys) / h,
                right=max(xs) / w,
                bottom=max(ys) / h,
            )
        )
    return [x for x in lines if x.text]


def ocr_easy(path: Path) -> List[Line]:
    import easyocr
    from PIL import Image

    im = Image.open(path).convert("RGB")
    w, h = im.size
    reader = easyocr.Reader(["ch_sim", "en"], gpu=False, verbose=False)
    result = reader.readtext(str(path))
    lines: List[Line] = []
    for box, text, _score in result:
        xs = [p[0] for p in box]
        ys = [p[1] for p in box]
        t = str(text).strip()
        if not t:
            continue
        lines.append(
            Line(
                text=t,
                left=min(xs) / w,
                top=min(ys) / h,
                right=max(xs) / w,
                bottom=max(ys) / h,
            )
        )
    return lines


def load_ocr() -> Tuple[str, Callable[[Path], List[Line]]]:
    try:
        import rapidocr_onnxruntime  # noqa: F401

        return "rapidocr", ocr_rapid
    except Exception:
        pass
    try:
        import easyocr  # noqa: F401

        return "easyocr", ocr_easy
    except Exception:
        pass
    print(
        "未找到 OCR 引擎。请先安装其一：\n"
        "  pip install rapidocr-onnxruntime pillow\n"
        "  或 pip install easyocr pillow",
        file=sys.stderr,
    )
    sys.exit(2)


# ---------- 镜像 App 几何（简化版，便于脚本侧迭代） ----------

BLOCKED_NICK = {
    "是的",
    "好的",
    "对的",
    "不是",
    "没有",
    "可以",
    "不行",
    "嗯嗯",
    "哈哈",
    "嗯",
    "哦",
    "啊",
    "哈",
    "对",
    "行",
    "微信",
    "对方",
    "群友",
    "豆姐",
}


def looks_like_sentence(text: str) -> bool:
    t = text.strip()
    if len(t) >= 8:
        return True
    return any(ch in t for ch in "，。！？、；：…,.!?;:")


def is_plausible_nick(name: str) -> bool:
    t = name.strip()
    if not t or t == "群友":
        return False
    if not (1 <= len(t) <= 20):
        return False
    if any(ch in t for ch in "，。！？、；：…,.!?;:@＠"):
        return False
    if t in BLOCKED_NICK:
        return False
    if any(len(b) >= 2 and t.endswith(b) for b in BLOCKED_NICK):
        return False
    if len(t) >= 3 and t[-1] in "了吗呢吧呀的":
        return False
    return True


def assign_side(line: Line) -> str:
    t = line.text.strip()
    # 贴左短串 = 群昵称，勿当 ME（与 App isLeftGroupNickname 一致）
    if (
        line.right <= 0.40
        and line.cx <= 0.32
        and 1 <= len(t) <= 14
        and not looks_like_sentence(t)
        and is_plausible_nick(t)
    ):
        return "OTHER"
    left_stick = line.left
    right_stick = 1.0 - line.right
    margin = 0.06
    if right_stick + margin < left_stick:
        return "ME"
    if left_stick + margin < right_stick:
        return "OTHER"
    if line.right >= 0.76:
        return "ME"
    if line.left <= 0.24 and line.right <= 0.72:
        return "OTHER"
    return "ME" if line.cx >= 0.52 else "OTHER"


def is_noise(line: Line) -> bool:
    t = line.text.strip()
    if not t:
        return True
    if line.bottom <= 0.055:
        return True
    if line.cy <= 0.10 and len(t) <= 14 and looks_like_sentence(t) is False:
        if not (line.left <= 0.42 and is_plausible_nick(t)):
            return True
    if line.top >= 0.92 and t in {"微信", "通讯录", "发现", "我"}:
        return True
    return False


def chat_font_h(lines: List[Line]) -> float:
    me_h = [x.height for x in lines if x.cx >= 0.50 or x.right >= 0.68]
    pool = me_h if len(me_h) >= 1 else [x.height for x in lines]
    if not pool:
        return 0.02
    pool = sorted(pool)
    return pool[int((len(pool) - 1) * 0.75)]


def format_with_nicks(raw: List[Line]) -> str:
    kept = [x for x in raw if not is_noise(x)]
    tagged = sorted(
        [(x, assign_side(x)) for x in kept],
        key=lambda p: (p[0].cy, p[0].left),
    )
    if not tagged:
        return ""
    ref_h = chat_font_h([x for x, _ in tagged])
    out: List[str] = []
    i = 0
    while i < len(tagged):
        line, side = tagged[i]
        text = line.text.strip().lstrip("|丨｜ \t")
        if side == "ME":
            out.append(f"我：{text}")
            i += 1
            continue
        nxt = tagged[i + 1] if i + 1 < len(tagged) else None
        looks_nick = False
        if nxt and nxt[1] == "OTHER":
            gap = nxt[0].top - line.bottom
            max_gap = max(line.height * 2.4, 0.045)
            looks_nick = (
                is_plausible_nick(text)
                and 1 <= len(text) <= 14
                and not looks_like_sentence(text)
                and -0.012 <= gap <= max_gap
                and line.left <= nxt[0].left + 0.10
                and line.cx <= nxt[0].cx + 0.06
                and line.cx < 0.48
                and nxt[0].text.strip() != text
            )
        if looks_nick and nxt is not None:
            body = nxt[0].text.strip().lstrip("|丨｜ \t")
            out.append(f"对方·{text}：{body}")
            i += 2
        else:
            out.append(f"对方：{text}")
            i += 1
    return "\n".join(out)


def contains_at(text: str, nick: str) -> bool:
    if not nick or not text:
        return False
    return re.search(r"[@＠]\s*" + re.escape(nick), text) is not None


def find_sender(chat: str, bot_nick: str) -> Tuple[Optional[str], Optional[str], str]:
    """返回 (memberName, triggerLine, reason)."""
    lines = [x.strip() for x in chat.splitlines() if x.strip()]
    candidates = []
    for idx, line in enumerate(lines):
        member, body = None, None
        if line.startswith("对方·") or line.startswith("对方･"):
            rest = line[3:]
            sep = rest.find("：")
            if sep < 0:
                sep = rest.find(":")
            if sep > 0:
                member = rest[:sep].strip() or "群友"
                body = rest[sep + 1 :].strip()
        elif line.startswith("对方："):
            member, body = "群友", line[3:].strip()
        elif line.startswith("对方:"):
            member, body = "群友", line[3:].strip()
        if body is None:
            continue
        if not contains_at(body, bot_nick):
            continue
        # 下方已有「我：」则视为已回复，跳过（与 App 一致）
        if any(lines[j].startswith("我：") or lines[j].startswith("我:") for j in range(idx + 1, len(lines))):
            continue
        candidates.append((idx, member or "群友", body))
    if not candidates:
        return None, None, "no_unanswered_at"
    # 从新到旧
    _idx, member, body = candidates[-1]
    return member, body, "ok"


# ---------- main ----------

def main() -> int:
    ap = argparse.ArgumentParser(description="群 Bot 发送人 OCR 批跑")
    ap.add_argument("--samples", type=Path, default=ROOT / "samples")
    ap.add_argument("--labels", type=Path, default=ROOT / "labels.json")
    ap.add_argument("--out", type=Path, default=ROOT / "out")
    args = ap.parse_args()

    if not args.labels.exists():
        print(f"缺少 labels: {args.labels}", file=sys.stderr)
        return 2
    labels = json.loads(args.labels.read_text(encoding="utf-8"))
    bot = labels.get("botNickname") or "豆姐"
    cases = labels.get("cases") or []

    engine_name, ocr_fn = load_ocr()
    args.out.mkdir(parents=True, exist_ok=True)

    results = []
    ok_n = fail_n = skip_n = 0

    print(f"OCR 引擎: {engine_name}")
    print(f"Bot 昵称: {bot}")
    print("-" * 60)

    for case in cases:
        cid = case.get("id") or case.get("file")
        fname = case.get("file")
        expected = (case.get("expectedSender") or "").strip()
        needle = case.get("triggerContains") or f"@{bot}"
        path = args.samples / fname
        if not path.exists() or "PUT_YOUR" in fname:
            print(f"[SKIP] {cid}: 文件不存在 → {path.name}")
            skip_n += 1
            results.append({"id": cid, "status": "skip", "reason": "missing_file"})
            continue

        try:
            raw_lines = ocr_fn(path)
            chat = format_with_nicks(raw_lines)
            member, trigger, reason = find_sender(chat, bot)
        except Exception as e:
            print(f"[FAIL] {cid}: OCR/解析异常: {e}")
            fail_n += 1
            results.append({"id": cid, "status": "fail", "reason": f"exception:{e}"})
            continue

        status = "fail"
        detail = ""
        if reason != "ok" or not trigger:
            detail = "没扫到未回复的 @"
        elif needle not in (trigger or "") and needle.replace("@", "＠") not in (trigger or ""):
            detail = f"触发句不含「{needle}」 got={trigger!r}"
        elif not is_plausible_nick(member or ""):
            detail = f"无可信昵称（got={member!r}）"
        elif (member or "") != expected:
            detail = f"认错人 expected={expected!r} got={member!r}"
        else:
            status = "pass"
            detail = f"OK @{member}"

        if status == "pass":
            ok_n += 1
            print(f"[PASS] {cid}: {detail}")
        else:
            fail_n += 1
            print(f"[FAIL] {cid}: {detail}")
            print(f"       trigger={trigger!r}")
            preview = " | ".join(chat.splitlines()[-6:])
            print(f"       chat_tail: {preview}")

        results.append(
            {
                "id": cid,
                "file": fname,
                "status": status,
                "detail": detail,
                "expectedSender": expected,
                "gotSender": member,
                "trigger": trigger,
                "chatText": chat,
                "lineCount": len(raw_lines),
            }
        )

    total = ok_n + fail_n
    rate = (ok_n / total * 100) if total else 0.0
    print("-" * 60)
    print(f"合计: pass={ok_n} fail={fail_n} skip={skip_n}  准确率={rate:.1f}%  (不含 skip)")

    out_path = args.out / "last_run.json"
    out_path.write_text(
        json.dumps(
            {
                "engine": engine_name,
                "botNickname": bot,
                "pass": ok_n,
                "fail": fail_n,
                "skip": skip_n,
                "accuracy": rate,
                "results": results,
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )
    print(f"明细已写: {out_path}")
    return 0 if fail_n == 0 and ok_n > 0 else (1 if fail_n else 0)


if __name__ == "__main__":
    raise SystemExit(main())
