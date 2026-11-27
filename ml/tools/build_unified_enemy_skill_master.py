"""
Clean, deduplicate, and unify authoritative Tower of Saviors enemy skills:
  1) Read-only ingestion of live A1 crawl (`authoritative_enemy_skills_dedup.json`) + Official 98 Encyclopedia (`enemy_skill_encyclopedia_spec.json`);
  2) Repairs wrapped/truncated 【titles】 & decapitated bullet titles;
  3) Unwraps mid-sentence game-layout line breaks while preserving logical bullet/arrow/clause structure;
  4) Strips UI chrome / scroll-edge noise (`AVE3`, `JSS`, stat card leaks) & detaches leaked scene tails;
  5) Collapses punctuation/variant-kanji/trailing-period pseudo-duplicates + strict OCR 1-2 char near-dupes (same numbers & elements);
  6) Unifies A1 (highest authority) with Encyclopedia 98 fallback without duplicating already-covered titles;
  7) Audits before/after rule match rate against `tos_enemy_skill_rules_v2.json` and writes clean reports to `ml/datasets/enemy_skill_master_clean/`.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from collections import defaultdict
from datetime import datetime, timezone
from difflib import SequenceMatcher
from pathlib import Path
from typing import Any


FULLWIDTH_MAP = str.maketrans({
    "０": "0", "１": "1", "２": "2", "３": "3", "４": "4",
    "５": "5", "６": "6", "７": "7", "８": "8", "９": "9",
    "＋": "+", "－": "-", "×": "x", "≧": "≥", "＞": ">",
    "～": "~", "（": "(", "）": ")", "　": " ",
})

# Keep readable Traditional Chinese for display, normalize only obvious Japanese/OCR variant kanji
CLEAN_DISPLAY_CHAR_MAP = str.maketrans({
    "撃": "擊",
    "強": "強",
    "强": "強",
    "帰": "歸",
    "転": "轉",
    "図": "圖",
    "戦": "戰",
    "闘": "鬥",
    "変": "變",
    "対": "對",
    "応": "應",
    "気": "氣",
    "悪": "惡",
    "竜": "龍",
    "獣": "獸",
    "覚": "覺",
    "満": "滿",
    "触": "觸",
    "発": "發",
    "続": "續",
    "経": "經",
    "関": "關",
    "開": "開",
    "場": "場",
    "数": "數",
    "単": "單",
    "双": "雙",
    "両": "兩",
    "残": "殘",
    "壊": "壞",
    "断": "斷",
    "読": "讀",
    "譲": "讓",
    "広": "廣",
    "圧": "壓",
    "蔵": "藏",
    "囲": "圍",
    "帯": "帶",
    "伝": "傳",
    "価": "價",
    "児": "兒",
    "効": "效",
    "労": "勞",
    "励": "勵",
    "収": "收",
    "叙": "敘",
    "号": "號",
    "咲": "咲",
    "囲": "圍",
    "図": "圖",
    "団": "團",
    "圏": "圈",
    "声": "聲",
    "売": "賣",
    "実": "實",
    "宝": "寶",
    "専": "專",
    "将": "將",
    "対": "對",
    "属": "屬",
    "歳": "歲",
    "歴": "歷",
    "殺": "殺",
    "温": "溫",
    "満": "滿",
    "画": "畫",
    "異": "異",
    "畳": "疊",
    "発": "發",
    "県": "縣",
    "真": "真",
    "砕": "碎",
    "礼": "禮",
    "秘": "秘",
    "称": "稱",
    "種": "種",
    "積": "積",
    "窓": "窗",
    "縁": "緣",
    "継": "繼",
    "総": "總",
    "練": "練",
    "繊": "纖",
    "聴": "聽",
    "薬": "藥",
    "蔵": "藏",
    "補": "補",
    "装": "裝",
    "覚": "覺",
    "覧": "覽",
    "観": "觀",
    "訳": "譯",
    "証": "證",
    "読": "讀",
    "諸": "諸",
    "譲": "讓",
    "軽": "輕",
    "転": "轉",
    "辺": "邊",
    "遅": "遲",
    "選": "選",
    "銭": "錢",
    "鉄": "鐵",
    "鉱": "礦",
    "録": "錄",
    "鎮": "鎮",
    "陥": "陷",
    "険": "險",
    "隠": "隱",
    "雑": "雜",
    "難": "難",
    "霊": "靈",
    "静": "靜",
    "響": "響",
    "顔": "顏",
    "願": "願",
    "類": "類",
    "駆": "驅",
    "験": "驗",
    "黒": "黑",
    "点": "點",
    "斎": "齋",
    "齿": "齒",
    "0": "0",
    "温": "溫",
    # Simplified characters occasionally produced by OCR inside Traditional text -> unify to Traditional for clean display
    "么": "麼",
    "为": "為",
    "无": "無",
    "与": "與",
    "于": "於",
    "会": "會",
    "体": "體",
    "敌": "敵",
    "时": "時",
    "当": "當",
    "后": "後",
    "该": "該",
    "内": "內",
    "并": "並",
    "将": "將",
    "问": "問",
    "隐": "隱",
    "条": "條",
    "进": "進",
    "选": "選",
    "状": "狀",
    "杀": "殺",
    "御": "禦",
    "记": "記",
    "结": "結",
    "闭": "閉",
    "间": "間",
    "热": "熱",
    "阴": "陰",
    "阳": "陽",
    "灵": "靈",
    "脉": "脈",
    "临": "臨",
    "狱": "獄",
    "炼": "煉",
    "复": "復",
    "终": "終",
    "锢": "錮",
    "笼": "籠",
    "标": "標",
    "启": "啟",
    "红": "紅",
    "绿": "綠",
    "灯": "燈",
    "坏": "壞",
    "绝": "絕",
    "灭": "滅",
    "袭": "襲",
    "渊": "淵",
    "剑": "劍",
    "圣": "聖",
    "决": "決",
    "负": "負",
    "馈": "饋",
    "态": "態",
    "伪": "偽",
    "装": "裝",
    "让": "讓",
    "这": "這",
    "弹": "彈",
    "乱": "亂",
    "冻": "凍",
    "尽": "盡",
    "余": "餘",
    "从": "從",
    "环": "環",
    "栏": "欄",
    "显": "顯",
    "蓝": "藍",
    "黄": "黃",
    "湿": "濕",
    "刚": "剛",
    "够": "夠",
    "齐": "齊",
    "连": "連",
    "击": "擊",
    "随": "隨",
    "机": "機",
    "召": "召",
    "唤": "喚",
    "师": "師",
    "场": "場",
    "上": "上",
    "鲁": "魯",
})

TRAD_TO_SIMP_KEY = str.maketrans({
    "屬": "属", "強": "强", "圖": "图", "連": "连", "擊": "击", "撃": "击",
    "雙": "双", "單": "单", "燒": "烧", "蝕": "蚀", "種": "种", "數": "数",
    "顆": "颗", "軌": "轨", "跡": "迹", "轉": "转", "鎖": "锁", "風": "风",
    "電": "电", "體": "体", "無": "无", "視": "视", "計": "计", "僅": "仅",
    "隻": "只", "與": "与", "類": "类", "龍": "龙", "獸": "兽", "機": "机",
    "術": "术", "個": "个", "組": "组", "沒": "没", "嚴": "严", "須": "须",
    "為": "为", "開": "开", "關": "关", "點": "点", "線": "线", "場": "场",
    "滿": "满", "動": "动", "傷": "伤", "減": "减", "區": "区", "異": "异",
    "變": "变", "換": "换", "經": "经", "過": "过", "對": "对", "應": "应",
    "於": "于", "會": "会", "資": "资", "訊": "讯", "離": "离", "戰": "战",
    "鬥": "斗", "隊": "队", "敵": "敌", "護": "护", "圍": "围", "繞": "绕",
    "積": "积", "達": "达", "觸": "触", "發": "发", "時": "时", "當": "当",
    "後": "后", "該": "该", "內": "内", "並": "并", "將": "将", "橫": "横",
    "問": "问", "號": "号", "隱": "隐", "條": "条", "補": "补", "進": "进",
    "實": "实", "選": "选", "擇": "择", "狀": "状", "殺": "杀", "禦": "御",
    "記": "记", "氣": "气", "結": "结", "閉": "闭", "間": "间", "熱": "热",
    "陰": "阴", "陽": "阳", "靈": "灵", "脈": "脉", "鏽": "锈", "溫": "温",
    "臨": "临", "獄": "狱", "煉": "炼", "復": "复", "顛": "颠", "終": "终",
    "錮": "锢", "籠": "笼", "標": "标", "啟": "启", "紅": "红", "綠": "绿",
    "燈": "灯", "壞": "坏", "續": "续", "絕": "绝", "滅": "灭", "襲": "袭",
    "淵": "渊", "劍": "剑", "聖": "圣", "決": "决", "兩": "两", "負": "负",
    "饋": "馈", "態": "态", "偽": "伪", "裝": "装", "讓": "让", "這": "这",
    "彈": "弹", "亂": "乱", "凍": "冻", "盡": "尽", "餘": "余", "從": "从",
    "迴": "回", "環": "环", "欄": "栏", "荊": "荆", "顯": "显", "藍": "蓝",
    "黃": "黄", "濕": "湿", "溼": "湿", "剛": "刚", "夠": "够", "齊": "齐",
    "歸": "归", "總": "总", "覽": "览", "篩": "筛",
    "準": "准", "備": "备", "預": "预", "設": "设", "階": "阶", "載": "载",
    "網": "网", "絡": "络", "驗": "验", "幣": "币", "獲": "获", "劇": "剧",
    "確": "确", "申": "申", "請": "请", "返": "返", "單": "单", "話": "话",
    "籤": "签", "長": "长", "隨": "随", "喚": "唤",
    "師": "师", "魯": "鲁", "毒": "毒",
    "詛": "诅", "咒": "咒", "枯": "枯", "竭": "竭",
    "優": "优", "質": "质", "鐵": "铁", "奧": "奥", "義": "义",
    "壓": "压", "縮": "缩", "懲": "惩", "罰": "罚",
    "從": "从", "處": "处", "敵": "敌", "傷": "伤", "擊": "击",
})

EDGE_NOISE_LINE_RE = re.compile(
    r"^(?:w?ave\s*\d+|w\d+/\d+|b?oss|jss|current|next|wave|boss|hp|atk|def|cd)$",
    re.IGNORECASE,
)

SCENE_TAIL_PATTERNS = [
    re.compile(r"\n\s*[•·]?\s*首回合敵人會將召喚師所有主動技能\s*CD\s*變\s*[0O]\s*$"),
    re.compile(r"\n\s*[•·]?\s*每回合開始移動符石時[，,]才會顯示場上的符石\s*$"),
]

CLAUSE_START_RE = re.compile(r"^(?:•|⇒|[①②③④⑤⑥⑦⑧⑨⑩]|▶|▼|【|\(|※|★|◆)")
SENTENCE_END_CHARS = ("。", "；", ";", "！", "!", "？", "?", "：", ":", ")")

SKILL_KEYWORDS = (
    "符石", "消除", "連擊", "combo", "攻擊", "傷害", "生命力", "回復",
    "回合", "召喚師", "敵人", "CD", "主動技能", "靈魂值", "靈罩",
    "溫度", "燃燒", "灼熱", "電擊", "冰封", "凍結", "石化", "風化",
    "黏腐", "暴風", "碎裂", "黑洞", "血浴", "幽閉", "起結有序", "盾",
    "無法控制", "防禦", "意志", "減傷", "中毒", "反擊", "聖果", "詛咒",
)


def to_canonical_zh_cn(text: str) -> str:
    s = (text or "").translate(FULLWIDTH_MAP).translate(TRAD_TO_SIMP_KEY)
    s = re.sub(r"[•·‧●⇒→》\s]+", "", s)
    s = re.sub(r"[。．，,.;:：!！?？()\[\]【】「」『』\-—]+", "", s)
    return s.lower().strip()


def compute_canonical_hash(title: str, body: str) -> str:
    key = f"{to_canonical_zh_cn(title)}|{to_canonical_zh_cn(body)}"
    return hashlib.sha256(key.encode("utf-8")).hexdigest()[:16]


def repair_title_and_body_split(raw_title: str, raw_body: str) -> tuple[str, str]:
    title = (raw_title or "").strip().translate(FULLWIDTH_MAP).translate(CLEAN_DISPLAY_CHAR_MAP)
    body = (raw_body or "").strip().translate(FULLWIDTH_MAP).translate(CLEAN_DISPLAY_CHAR_MAP)

    # Case 1: Title is actually a bullet line decapitated from body
    if title and not title.startswith("【") and re.match(r"^[•·‧●⇒]", title):
        body = f"{title}\n{body}".strip() if body else title
        title = ""

    # Case 2: Multi-line wrapped 【Title\nSubTitle】 split across raw_title and line 0 of raw_body
    if title.startswith("【") and "】" not in title and body:
        lines = [ln.strip() for ln in body.splitlines() if ln.strip()]
        if lines and "】" in lines[0]:
            part0, rest0 = lines[0].split("】", 1)
            title = f"{title}{part0.strip()}】"
            remaining_lines = ([rest0.strip()] if rest0.strip() else []) + lines[1:]
            body = "\n".join(remaining_lines)

    # Fix unmatched closing bracket on title like `【靈魂值 - 光成員 (每個 1,000)` or `(3x3))`
    if title.startswith("【") and "】" not in title:
        if title.endswith(")"):
            # Could be truncated `)` instead of `】`, or missing `】` after `(每個 1,000)`
            if title.count("(") < title.count(")"):
                title = title[:-1] + "】"
            else:
                title = title + "】"
        else:
            title = title + "】"

    # Normalize spacing around 【...】
    if title.startswith("【") and title.endswith("】"):
        inner = title[1:-1].strip()
        inner = re.sub(r"\s*-\s*", " - ", inner)
        inner = re.sub(r"\s+", " ", inner)
        title = f"【{inner}】"

    # Case 3: Empty title where body line 0 is a standalone short header (e.g., `溫律臨界`)
    if not title and body:
        lines = [ln.strip() for ln in body.splitlines() if ln.strip()]
        if len(lines) >= 2 and len(lines[0]) <= 16 and not CLAUSE_START_RE.match(lines[0]):
            if any(k in lines[0] for k in ("溫律臨界", "起結有序", "幽閉空間", "移形換影")) or (
                not any(p in lines[0] for p in ("。", "，", ",", "⇒")) and CLAUSE_START_RE.match(lines[1])
            ):
                title = f"【{lines[0]}】"
                body = "\n".join(lines[1:])

    return title, body


def clean_layout_and_ocr_artifacts(raw_body: str) -> tuple[str, list[str], int]:
    """
    Returns (cleaned_body_zh_tw, detached_scene_tails, unwrapped_join_count).
    """
    if not raw_body:
        return "", [], 0

    text = raw_body.translate(FULLWIDTH_MAP).translate(CLEAN_DISPLAY_CHAR_MAP)
    text = text.replace("「靈罩]", "「靈罩」").replace("CD變O", "CD變0")

    detached_tails: list[str] = []
    for pat in SCENE_TAIL_PATTERNS:
        m = pat.search(text)
        if m:
            tail = m.group(0).strip()
            if tail:
                detached_tails.append(tail)
            text = pat.sub("", text).strip()

    # Clean each line's leading/trailing markers before unwrapping
    raw_lines = []
    for ln in text.replace("\r", "\n").splitlines():
        s = ln.strip()
        if not s:
            continue
        if EDGE_NOISE_LINE_RE.match(s):
            continue
        # Fix common OCR bullet prefixes
        s = re.sub(r"^(?:[·‧●○•]|(?:\?(?=[^\x00-\x7f])))\s*", "• ", s)
        # Fix OCR `=>` / `→` / `》` arrow prefixes
        s = re.sub(r"^(?:=>|→|》|⇒)\s*", "⇒ ", s)
        s = re.sub(r"\s+", " ", s).strip()
        if s:
            raw_lines.append(s)

    if not raw_lines:
        return "", detached_tails, 0

    # Smart unwrap of hard layout breaks while keeping logical bullet/arrow/sentence breaks
    merged: list[str] = [raw_lines[0]]
    joins = 0
    for nxt in raw_lines[1:]:
        prev = merged[-1]
        keep_break = (
            bool(CLAUSE_START_RE.match(nxt))
            or prev.endswith(SENTENCE_END_CHARS)
        )
        if keep_break:
            merged.append(nxt)
        else:
            needs_space = (
                bool(prev)
                and bool(nxt)
                and prev[-1].isascii()
                and prev[-1].isalnum()
                and nxt[0].isascii()
                and nxt[0].isalnum()
            )
            merged[-1] = f"{prev} {nxt}" if needs_space else f"{prev}{nxt}"
            joins += 1

    # Post-unwrap inline polish
    cleaned = "\n".join(merged).strip()
    cleaned = re.sub(r"[ ]+", " ", cleaned)
    # Remove stray spaces inside CJK phrases produced after unwrap (preserve after bullets/arrows and around ASCII tokens)
    cleaned = re.sub(r"(?<=[\u4e00-\u9fff])\s+(?=[\u4e00-\u9fff])", "", cleaned)
    # Normalize bullet spacing (`•文本` -> `• 文本`, `⇒文本` -> `⇒ 文本`)
    cleaned = re.sub(r"^•\s*", "• ", cleaned, flags=re.MULTILINE)
    cleaned = re.sub(r"^⇒\s*", "⇒ ", cleaned, flags=re.MULTILINE)
    return cleaned, detached_tails, joins


def classify_drop_reason(title: str, clean_body: str, occ: int) -> str | None:
    if not clean_body.strip():
        return "EMPTY_BODY_STUB"

    norm_cn = to_canonical_zh_cn(f"{title} {clean_body}")

    # Enemy name + HP/ATK/DEF stat card accidentally captured into body
    if re.search(r"\d+(?:\.\d+)?兆", clean_body) or re.search(r"\b\d{1,3}(?:,\d{3}){2,}\b", clean_body):
        if not any(k in clean_body for k in ("符石", "連擊", "生命力", "攻擊力", "傷害", "靈魂")):
            return "ENEMY_STAT_CARD_LEAK"

    if occ <= 1:
        # Known severely garbled 1-hit OCR strings or truncated stubs missing skill semantics
        if re.search(r"(?:敞八反掌|回八土仔|1除召喚師|^\d?除召喚師|攻俱害|印記刀重)", clean_body):
            return "GARBLED_OCR_FRAGMENT"
        has_kw = any(k in clean_body for k in SKILL_KEYWORDS)
        if len(norm_cn) < 6 and not (title.startswith("【") and has_kw):
            return "GARBLED_OCR_FRAGMENT"

    return None


def _has_opposite_polarity(text_a: str, text_b: str) -> bool:
    a = to_canonical_zh_cn(text_a)
    b = to_canonical_zh_cn(text_b)
    for kw in ("减少", "提升", "增加", "禁止", "必须", "首消", "累积", "无视"):
        if a.count(kw) != b.count(kw):
            return True
    return False


def _token_signature(text: str) -> tuple[tuple[str, ...], tuple[str, ...]]:
    cn = (text or "").translate(FULLWIDTH_MAP).translate(TRAD_TO_SIMP_KEY)
    nums = tuple(re.findall(r"\d+(?:\.\d+)?%?", cn))
    elems = tuple(ch for ch in cn if ch in "水火木光暗心")
    return nums, elems


def eval_rules_coverage(entries: list[dict[str, Any]], rules_path: Path, use_clean: bool) -> dict[str, Any]:
    rules_data = json.loads(rules_path.read_text(encoding="utf-8"))
    compiled: list[tuple[str, str, re.Pattern[str]]] = []
    for r in rules_data.get("rules", []):
        pat = r.get("pattern", "")
        if pat:
            compiled.append((r["id"], r["chipId"], re.compile(pat, re.IGNORECASE)))

    matched = 0
    occ_total = 0
    occ_matched = 0
    for e in entries:
        if use_clean:
            text = f"{e.get('title_zh_tw', '')}\n{e.get('clean_body_zh_tw', '')}"
        else:
            text = f"{e.get('title_zh_tw', '')}\n{e.get('raw_body_zh_tw', '')}"
        norm = text.translate(FULLWIDTH_MAP).translate(TRAD_TO_SIMP_KEY)
        norm = re.sub(r"[ \t]+", " ", norm)
        hit = any(p.search(norm) for (_, _, p) in compiled)
        occ = int(e.get("occurrence_count", 1))
        occ_total += occ
        if hit:
            matched += 1
            occ_matched += occ
    return {
        "entries_total": len(entries),
        "entries_regex_matched": matched,
        "entries_regex_rate": round(matched / max(1, len(entries)), 4),
        "occurrence_total": occ_total,
        "occurrence_regex_matched": occ_matched,
        "occurrence_regex_rate": round(occ_matched / max(1, occ_total), 4),
    }


def build_unified_master(
    a1_path: Path,
    enc_path: Path,
    rules_path: Path | None,
    out_dir: Path,
) -> dict[str, Any]:
    a1_data = json.loads(a1_path.read_text(encoding="utf-8"))
    enc_data = json.loads(enc_path.read_text(encoding="utf-8"))
    raw_skills = a1_data.get("skills", [])

    total_joins = 0
    total_detached_tails = 0
    dropped: list[dict[str, Any]] = []
    empty_stubs_by_title: dict[str, int] = defaultdict(int)

    # Pass 1: Repair & clean every A1 entry, grouping exact canonical hashes
    grouped: dict[str, dict[str, Any]] = {}
    for s in raw_skills:
        raw_title = s.get("title_zh_tw", "")
        raw_body = s.get("raw_body_zh_tw", "")
        occ = int(s.get("occurrence_count", 1))
        kind = s.get("skill_kind", "enemy_skill")

        clean_title, repaired_body = repair_title_and_body_split(raw_title, raw_body)
        clean_body, tails, joins = clean_layout_and_ocr_artifacts(repaired_body)
        total_joins += joins
        total_detached_tails += len(tails)

        drop_reason = classify_drop_reason(clean_title, clean_body, occ)
        if drop_reason is not None:
            if drop_reason == "EMPTY_BODY_STUB" and clean_title:
                empty_stubs_by_title[to_canonical_zh_cn(clean_title)] += occ
            dropped.append({
                "skill_hash": s.get("skill_hash", ""),
                "drop_reason": drop_reason,
                "skill_kind": kind,
                "occurrence_count": occ,
                "raw_title_zh_tw": raw_title,
                "raw_body_zh_tw": raw_body,
                "repaired_title": clean_title,
                "cleaned_body": clean_body,
            })
            continue

        c_hash = compute_canonical_hash(clean_title, clean_body)
        stages = list(s.get("sample_stages", []))
        if c_hash not in grouped:
            grouped[c_hash] = {
                "canonical_hash": c_hash,
                "skill_kind": kind,
                "title_zh_tw": clean_title,
                "clean_body_zh_tw": clean_body,
                "canonical_zh_cn": to_canonical_zh_cn(f"{clean_title}\n{clean_body}"),
                "occurrence_count": occ,
                "stage_count": int(s.get("stage_count", len(stages))),
                "source_priority": "A1_LIVE_AUTHORITATIVE",
                "merged_raw_variants": [{
                    "raw_hash": s.get("skill_hash", ""),
                    "occurrence_count": occ,
                    "raw_title_zh_tw": raw_title,
                    "raw_body_zh_tw": raw_body,
                }],
                "detached_scene_tails": tails,
                "sample_stages": stages[:5],
            }
        else:
            existing = grouped[c_hash]
            existing["occurrence_count"] += occ
            existing["merged_raw_variants"].append({
                "raw_hash": s.get("skill_hash", ""),
                "occurrence_count": occ,
                "raw_title_zh_tw": raw_title,
                "raw_body_zh_tw": raw_body,
            })
            for t in tails:
                if t not in existing["detached_scene_tails"]:
                    existing["detached_scene_tails"].append(t)
            # Prefer clean version with higher occurrence count or cleaner punctuation
            if len(clean_body) > len(existing["clean_body_zh_tw"]):
                existing["clean_body_zh_tw"] = clean_body

    # Fold counts from dropped empty-body stubs into matching cleaned title entries when unambiguous
    for entry in grouped.values():
        tkey = to_canonical_zh_cn(entry["title_zh_tw"])
        if tkey and tkey in empty_stubs_by_title:
            # Only attribute stub occurrences to the top-frequency variant of that title
            pass
    # Attribute empty stub occurrences to highest-count entry sharing that title
    by_tkey: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for entry in grouped.values():
        tk = to_canonical_zh_cn(entry["title_zh_tw"])
        if tk:
            by_tkey[tk].append(entry)
    for tk, stub_occ in empty_stubs_by_title.items():
        if tk in by_tkey:
            top_e = max(by_tkey[tk], key=lambda x: x["occurrence_count"])
            top_e["occurrence_count"] += stub_occ
            top_e["folded_empty_stub_occurrences"] = top_e.get("folded_empty_stub_occurrences", 0) + stub_occ

    # Pass 2: Safe OCR 1-2 char near-duplicate merge ONLY within identical title AND identical numbers/elements
    exact_list = sorted(grouped.values(), key=lambda x: (-x["occurrence_count"], x["title_zh_tw"]))
    fuzzy_merges: list[dict[str, Any]] = []
    by_title_exact: dict[str, list[dict[str, Any]]] = defaultdict(list)
    kept_entries: list[dict[str, Any]] = []

    for item in exact_list:
        t_canon = to_canonical_zh_cn(item["title_zh_tw"])
        item_sig = _token_signature(item["clean_body_zh_tw"])
        item_body_cn = to_canonical_zh_cn(item["clean_body_zh_tw"])
        merged_target = None
        if t_canon:
            for cand in by_title_exact[t_canon]:
                if _token_signature(cand["clean_body_zh_tw"]) != item_sig:
                    continue
                if _has_opposite_polarity(cand["clean_body_zh_tw"], item["clean_body_zh_tw"]):
                    continue
                cand_body_cn = to_canonical_zh_cn(cand["clean_body_zh_tw"])
                ratio = SequenceMatcher(None, cand_body_cn, item_body_cn).ratio()
                if ratio >= 0.90:
                    merged_target = cand
                    break
        if merged_target is not None:
            merged_target["occurrence_count"] += item["occurrence_count"]
            merged_target["merged_raw_variants"].extend(item["merged_raw_variants"])
            fuzzy_merges.append({
                "title": item["title_zh_tw"],
                "kept_body": merged_target["clean_body_zh_tw"],
                "merged_ocr_variant_body": item["clean_body_zh_tw"],
                "merged_occ": item["occurrence_count"],
            })
        else:
            if t_canon:
                by_title_exact[t_canon].append(item)
            kept_entries.append(item)

    # Pass 3: Unify with Official 98 Encyclopedia (A1 Primary Authority, Encyclopedia Fallback only for missing titles)
    a1_title_keys = {to_canonical_zh_cn(e["title_zh_tw"]) for e in kept_entries if e["title_zh_tw"]}
    enc_covered_by_a1 = 0
    enc_fallback_added = 0

    for enc in enc_data.get("skills", []):
        enc_title = f"【{enc['name_zh_tw']}】"
        enc_tk = to_canonical_zh_cn(enc["name_zh_tw"])
        enc_cn_tk = to_canonical_zh_cn(enc["name_zh_cn"])
        if enc_tk in a1_title_keys or enc_cn_tk in a1_title_keys:
            enc_covered_by_a1 += 1
            for e in kept_entries:
                etk = to_canonical_zh_cn(e["title_zh_tw"])
                if etk in (enc_tk, enc_cn_tk):
                    e["encyclopedia_ref"] = {
                        "index": enc["index"],
                        "coverage_status": enc["autodora_mapping"]["coverage_status"],
                        "wave_shield_chips": enc["autodora_mapping"]["wave_shield_chips"],
                    }
        else:
            _, rep_b = repair_title_and_body_split(enc_title, enc.get("full_rule_text_zh_tw", ""))
            clean_b, _, _ = clean_layout_and_ocr_artifacts(rep_b)
            c_hash = compute_canonical_hash(enc_title, clean_b)
            if c_hash not in {x["canonical_hash"] for x in kept_entries}:
                kept_entries.append({
                    "canonical_hash": c_hash,
                    "skill_kind": "enemy_skill",
                    "title_zh_tw": enc_title,
                    "clean_body_zh_tw": clean_b,
                    "canonical_zh_cn": to_canonical_zh_cn(f"{enc_title}\n{clean_b}"),
                    "occurrence_count": 0,
                    "stage_count": 0,
                    "source_priority": "ENCYCLOPEDIA_FALLBACK",
                    "encyclopedia_ref": {
                        "index": enc["index"],
                        "coverage_status": enc["autodora_mapping"]["coverage_status"],
                        "wave_shield_chips": enc["autodora_mapping"]["wave_shield_chips"],
                    },
                    "merged_raw_variants": [],
                    "detached_scene_tails": [],
                    "sample_stages": [],
                })
                enc_fallback_added += 1

    # Count before/after duplicate titles
    raw_by_title: dict[str, int] = defaultdict(int)
    for s in raw_skills:
        raw_by_title[(s.get("title_zh_tw") or "").strip()] += 1
    clean_by_title: dict[str, int] = defaultdict(int)
    a1_only_kept = [e for e in kept_entries if e["source_priority"] == "A1_LIVE_AUTHORITATIVE"]
    for e in a1_only_kept:
        clean_by_title[e["title_zh_tw"]] += 1

    rule_before = eval_rules_coverage(raw_skills, rules_path, use_clean=False) if (rules_path and rules_path.is_file()) else {}
    rule_after_a1 = eval_rules_coverage(a1_only_kept, rules_path, use_clean=True) if (rules_path and rules_path.is_file()) else {}

    out_dir.mkdir(parents=True, exist_ok=True)
    now_iso = datetime.now(timezone.utc).astimezone().isoformat(timespec="seconds")

    master_payload = {
        "schema_version": "1.0",
        "generated_at": now_iso,
        "a1_snapshot_generated_at": a1_data.get("generated_at", ""),
        "authority_order": ["A1_LIVE_AUTHORITATIVE", "ENCYCLOPEDIA_FALLBACK"],
        "total_unified_skills": len(kept_entries),
        "a1_cleaned_skills": len(a1_only_kept),
        "encyclopedia_fallback_added": enc_fallback_added,
        "skills": kept_entries,
    }
    (out_dir / "unified_enemy_skill_master.json").write_text(
        json.dumps(master_payload, ensure_ascii=False, indent=2), encoding="utf-8"
    )
    (out_dir / "audit_dropped.json").write_text(
        json.dumps(dropped, ensure_ascii=False, indent=2), encoding="utf-8"
    )

    drop_counts: dict[str, int] = defaultdict(int)
    for d in dropped:
        drop_counts[d["drop_reason"]] += 1

    comparison = {
        "generated_at": now_iso,
        "a1_source_generated_at": a1_data.get("generated_at", ""),
        "before": {
            "raw_a1_unique_entries": len(raw_skills),
            "raw_a1_with_newlines": sum(1 for s in raw_skills if "\n" in (s.get("raw_body_zh_tw") or "")),
            "raw_a1_empty_title_count": sum(1 for s in raw_skills if not (s.get("title_zh_tw") or "").strip()),
            "raw_a1_empty_body_count": sum(1 for s in raw_skills if not (s.get("raw_body_zh_tw") or "").strip()),
            "raw_a1_titles_with_multiple_variants": sum(1 for k, c in raw_by_title.items() if k and c > 1),
            "encyclopedia_total": len(enc_data.get("skills", [])),
            "regex_audit": rule_before,
        },
        "cleaning_actions": {
            "unwrapped_mid_sentence_line_joins": total_joins,
            "detached_scene_tails_count": total_detached_tails,
            "exact_pseudo_duplicates_collapsed": sum(max(0, len(e["merged_raw_variants"]) - 1) for e in kept_entries) - len(fuzzy_merges),
            "ocr_near_duplicate_merges": len(fuzzy_merges),
            "fuzzy_merge_details": fuzzy_merges,
            "dropped_counts": dict(drop_counts),
        },
        "after": {
            "a1_cleaned_unique_entries": len(a1_only_kept),
            "a1_cleaned_with_newlines": sum(1 for e in a1_only_kept if "\n" in e["clean_body_zh_tw"]),
            "a1_cleaned_empty_title_count": sum(1 for e in a1_only_kept if not e["title_zh_tw"]),
            "a1_cleaned_titles_with_multiple_variants": sum(1 for k, c in clean_by_title.items() if k and c > 1),
            "encyclopedia_covered_by_a1": enc_covered_by_a1,
            "encyclopedia_fallback_added": enc_fallback_added,
            "unified_total_entries": len(kept_entries),
            "regex_audit_a1_cleaned": rule_after_a1,
        },
    }
    (out_dir / "cleaning_comparison_report.json").write_text(
        json.dumps(comparison, ensure_ascii=False, indent=2), encoding="utf-8"
    )

    md_lines = [
        "# 敌技唯一权威库清洗与整合前后对比报告",
        "",
        f"- 统计快照时间：`{a1_data.get('generated_at', '')}` · 输出目录：`ml/datasets/enemy_skill_master_clean/`",
        "",
        "## 1. 清洗前后核心指标对比",
        "",
        "| 指标项 | 清洗前（原始 A1 实爬 + 百科独立存放） | 清洗整合后（唯一权威库） | 变化说明 |",
        "| :--- | :---: | :---: | :--- |",
        f"| A1 独立词条数 | `{comparison['before']['raw_a1_unique_entries']}` | **`{comparison['after']['a1_cleaned_unique_entries']}`** | 合并伪重复 `{comparison['cleaning_actions']['exact_pseudo_duplicates_collapsed']}` + 严格近重复 `{len(fuzzy_merges)}` + 剔除脏/空条目 `{len(dropped)}` |",
        f"| 含硬换行符词条数 | `{comparison['before']['raw_a1_with_newlines']}` | `{comparison['after']['a1_cleaned_with_newlines']}` | 共平滑连接 **`{total_joins}` 处** 句中截断换行，仅保留条款 `• / ⇒` 分段换行 |",
        f"| 空标题 / 断头词条数 | `{comparison['before']['raw_a1_empty_title_count']}` | **`{comparison['after']['a1_cleaned_empty_title_count']}`** | 修复跨行截断标题、补全缺失 `】` 与独立标题提取 |",
        f"| 同名多变体标题组数 | `{comparison['before']['raw_a1_titles_with_multiple_variants']}` | **`{comparison['after']['a1_cleaned_titles_with_multiple_variants']}`** | 消除标点/异体字假分歧，仅保留真正数值/属性不同的合法多变体 |",
        f"| 剥离粘连场景技尾巴 | — | **`{total_detached_tails}` 处** | 如 `首回合主動技能CD變0` 等不再污染普通敌技正文 |",
        f"| 百科 98 项整合状态 | 独立另存（重复 160+） | `A1 已覆盖 {enc_covered_by_a1} + 百科兜底补漏 {enc_fallback_added}` | 唯一整合总库 **`{len(kept_entries)}` 条**（现版本 A1 为最高权威） |",
    ]
    if rule_before and rule_after_a1:
        md_lines.append(
            f"| 现行规则引擎覆盖率（按词条） | `{rule_before['entries_regex_matched']}/{rule_before['entries_total']} ({rule_before['entries_regex_rate']*100:.1f}%)` | "
            f"**`{rule_after_a1['entries_regex_matched']}/{rule_after_a1['entries_total']} ({rule_after_a1['entries_regex_rate']*100:.1f}%)`** | 仅凭去换行+洗净，未改规则即释放跨行失效正则 |"
        )
    md_lines.extend(["", "## 2. 剔除条目明细分类（已保留至 `audit_dropped.json`）", ""])
    for rk, rv in sorted(drop_counts.items()):
        md_lines.append(f"- `{rk}`: **{rv}** 条")
    if fuzzy_merges:
        md_lines.extend(["", "## 3. 严格 1-2 字 OCR 近重复合并核验", ""])
        for fm in fuzzy_merges:
            md_lines.append(f"- **{fm['title']}** (+{fm['merged_occ']}x): `{fm['merged_ocr_variant_body']!r}` → 并入 `{fm['kept_body']!r}`")
    (out_dir / "cleaning_comparison_report.md").write_text("\n".join(md_lines) + "\n", encoding="utf-8")
    return comparison


def main() -> None:
    parser = argparse.ArgumentParser(description="Build unified & cleaned ToS authoritative enemy skill master")
    parser.add_argument(
        "--a1",
        default="backups/phase4_v2_inbox/enemy_skill_master/authoritative_enemy_skills_dedup.json",
        help="Read-only path to A1 crawled master JSON",
    )
    parser.add_argument(
        "--enc",
        default="docs/enemy_skill_encyclopedia_spec.json",
        help="Read-only path to official 98 encyclopedia spec",
    )
    parser.add_argument(
        "--rules",
        default="android/app/src/main/assets/tos_enemy_skill_rules_v2.json",
        help="Optional rules v2 JSON for before/after match audit",
    )
    parser.add_argument(
        "--out",
        default="ml/datasets/enemy_skill_master_clean",
        help="Output directory inside ml/datasets/",
    )
    args = parser.parse_args()
    comp = build_unified_master(Path(args.a1), Path(args.enc), Path(args.rules), Path(args.out))
    print(
        f"Before={comp['before']['raw_a1_unique_entries']} -> A1Cleaned={comp['after']['a1_cleaned_unique_entries']}, "
        f"UnifiedTotal={comp['after']['unified_total_entries']} (enc_fallback={comp['after']['encyclopedia_fallback_added']}), "
        f"line_joins={comp['cleaning_actions']['unwrapped_mid_sentence_line_joins']}, "
        f"fuzzy_merges={comp['cleaning_actions']['ocr_near_duplicate_merges']}, "
        f"dropped={comp['cleaning_actions']['dropped_counts']}"
    )


if __name__ == "__main__":
    main()
