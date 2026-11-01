"""HTML / Markdown to Telegram message entities. Offsets are UTF-16 code units,
as Telegram requires. Covers the tags exteraGram plugins use."""
import re
from enum import Enum
from html.parser import HTMLParser
from typing import Any, Dict, List, Optional

from java import jclass


class TLEntityType(Enum):
    BOLD = "bold"
    ITALIC = "italic"
    UNDERLINE = "underline"
    STRIKETHROUGH = "strike"
    CODE = "code"
    PRE = "pre"
    TEXT_LINK = "text_link"
    SPOILER = "spoiler"
    CUSTOM_EMOJI = "custom_emoji"
    BLOCKQUOTE = "blockquote"


def _u16len(s):
    return len(s.encode("utf-16-le")) // 2


class RawEntity:
    def __init__(self, type, offset, length, url=None, language=None, document_id=None, collapsed=False):
        self.type = type
        self.offset = offset
        self.length = length
        self.url = url
        self.language = language
        self.document_id = document_id
        self.collapsed = collapsed

    def to_tlrpc_object(self):
        TLRPC = jclass("org.telegram.tgnet.TLRPC")
        t = self.type
        if t == TLEntityType.BOLD:
            e = TLRPC.TL_messageEntityBold()
        elif t == TLEntityType.ITALIC:
            e = TLRPC.TL_messageEntityItalic()
        elif t == TLEntityType.UNDERLINE:
            e = TLRPC.TL_messageEntityUnderline()
        elif t == TLEntityType.STRIKETHROUGH:
            e = TLRPC.TL_messageEntityStrike()
        elif t == TLEntityType.CODE:
            e = TLRPC.TL_messageEntityCode()
        elif t == TLEntityType.PRE:
            e = TLRPC.TL_messageEntityPre()
            e.language = self.language or ""
        elif t == TLEntityType.TEXT_LINK:
            e = TLRPC.TL_messageEntityTextUrl()
            e.url = self.url or ""
        elif t == TLEntityType.SPOILER:
            e = TLRPC.TL_messageEntitySpoiler()
        elif t == TLEntityType.CUSTOM_EMOJI:
            e = TLRPC.TL_messageEntityCustomEmoji()
            e.document_id = int(self.document_id or 0)
        elif t == TLEntityType.BLOCKQUOTE:
            e = TLRPC.TL_messageEntityBlockquote()
            try:
                e.collapsed = bool(self.collapsed)
            except Exception:
                pass
        else:
            e = TLRPC.TL_messageEntityBold()
        e.offset = int(self.offset)
        e.length = int(self.length)
        return e


_HTML_TAGS = {
    "b": TLEntityType.BOLD, "strong": TLEntityType.BOLD,
    "i": TLEntityType.ITALIC, "em": TLEntityType.ITALIC,
    "u": TLEntityType.UNDERLINE, "ins": TLEntityType.UNDERLINE,
    "s": TLEntityType.STRIKETHROUGH, "strike": TLEntityType.STRIKETHROUGH, "del": TLEntityType.STRIKETHROUGH,
    "code": TLEntityType.CODE, "pre": TLEntityType.PRE,
    "a": TLEntityType.TEXT_LINK, "blockquote": TLEntityType.BLOCKQUOTE,
    "tg-spoiler": TLEntityType.SPOILER, "tg-emoji": TLEntityType.CUSTOM_EMOJI,
}


class _Collector(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.out = []
        self.len = 0
        self.open = []
        self.entities = []

    def _push_text(self, text):
        self.out.append(text)
        self.len += _u16len(text)

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        t = _HTML_TAGS.get(tag)
        if tag == "span" and a.get("class") == "tg-spoiler":
            t = TLEntityType.SPOILER
        if t is None:
            return
        self.open.append((t, self.len, a))

    def handle_endtag(self, tag):
        t = _HTML_TAGS.get(tag)
        if tag == "span":
            t = TLEntityType.SPOILER
        for i in range(len(self.open) - 1, -1, -1):
            ot, ostart, oattr = self.open[i]
            if ot == t:
                length = self.len - ostart
                if length > 0:
                    self.entities.append(RawEntity(
                        ot, ostart, length,
                        url=oattr.get("href"),
                        language=oattr.get("language") or (oattr.get("class", "").replace("language-", "") if tag == "code" else None),
                        document_id=oattr.get("emoji-id") or oattr.get("data-document-id"),
                    ))
                del self.open[i]
                return

    def handle_data(self, data):
        self._push_text(data)


def parse_html(text):
    p = _Collector()
    p.feed(str(text))
    p.close()
    return "".join(p.out), p.entities


_MD_PATTERNS = [
    (re.compile(r"```(\w*)\n?(.*?)```", re.S), TLEntityType.PRE),
    (re.compile(r"`([^`\n]+)`"), TLEntityType.CODE),
    (re.compile(r"\*\*(.+?)\*\*", re.S), TLEntityType.BOLD),
    (re.compile(r"__(.+?)__", re.S), TLEntityType.UNDERLINE),
    (re.compile(r"~~(.+?)~~", re.S), TLEntityType.STRIKETHROUGH),
    (re.compile(r"\|\|(.+?)\|\|", re.S), TLEntityType.SPOILER),
    (re.compile(r"(?<!\*)\*(?!\*)(.+?)(?<!\*)\*(?!\*)", re.S), TLEntityType.ITALIC),
    (re.compile(r"_(.+?)_", re.S), TLEntityType.ITALIC),
]
_MD_LINK = re.compile(r"\[(.+?)\]\((.+?)\)", re.S)


def parse_markdown(text):
    text = str(text)
    entities = []

    def replace_link(m):
        inner = m.group(1)
        start = _u16len(result_before(m.start()))
        return m.group(0)  # handled below in a second pass

    # Links first.
    out = []
    pos = 0
    plain = []
    cur_len = 0
    for m in _MD_LINK.finditer(text):
        plain.append(text[pos:m.start()])
        cur_len += _u16len(text[pos:m.start()])
        label = m.group(1)
        entities.append(RawEntity(TLEntityType.TEXT_LINK, cur_len, _u16len(label), url=m.group(2)))
        plain.append(label)
        cur_len += _u16len(label)
        pos = m.end()
    plain.append(text[pos:])
    text = "".join(plain)

    for pattern, etype in _MD_PATTERNS:
        while True:
            m = pattern.search(text)
            if not m:
                break
            if etype == TLEntityType.PRE:
                lang, inner = m.group(1), m.group(2)
            else:
                lang, inner = None, m.group(1)
            offset = _u16len(text[:m.start()])
            text = text[:m.start()] + inner + text[m.end():]
            # shift existing entities after the removed markers
            removed_before = _u16len(m.group(0)) - _u16len(inner)
            for e in entities:
                if e.offset >= offset:
                    e.offset -= 0
            entities.append(RawEntity(etype, offset, _u16len(inner), language=lang))
    return text, entities


def parse_text(text, parse_mode="HTML", is_caption=False):
    mode = (parse_mode or "HTML").upper()
    if mode == "MARKDOWN":
        plain, raw = parse_markdown(text)
    else:
        plain, raw = parse_html(text)
    entities = [r.to_tlrpc_object() for r in raw if r.length > 0]
    key = "caption" if is_caption else "message"
    return {key: plain, "entities": entities}
