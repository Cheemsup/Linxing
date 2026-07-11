"""
parsers 子包共享工具模块

仅收录 PDF / DOCX 等结构化解析器共同需要的小工具，避免在 pdf_parser / docx_parser
之间重复实现。其它纯文本解析器（markdown / html / code / linebased）历史上各自
内联了等价辅助，沿用其自包含风格不动，不在本模块强制收敛。

约定：本模块只放无状态、可复用的纯函数；不放任何解析器实例或路由逻辑。
"""

import hashlib
import re
from typing import Iterable, List, Optional, Tuple


def make_node_seq():
    """
    自管递增 Node id 生成器：n1, n2, ...
    与各文本解析器内联的 _make_node_seq 行为一致，供 pdf / docx 解析器复用。
    """
    i = 0
    while True:
        i += 1
        yield f"n{i}"


def build_title_path(title_stack: Iterable[Tuple[int, str]]) -> Optional[str]:
    """
    根据标题栈构建 titlePath（"一级 > 二级"），栈空返回 None。

    :param title_stack: [(level, title), ...]，按层级顺序压栈
    """
    parts = [title for _, title in title_stack]
    if not parts:
        return None
    return " > ".join(parts)


def compute_hash(data: bytes) -> str:
    """计算 bytes 的 MD5 哈希（用于图片去重）。"""
    return hashlib.md5(data).hexdigest()


def guess_image_ext(content_type: str) -> str:
    """根据 MIME 类型猜测图片扩展名，未知类型回退 png。"""
    mapping = {
        "image/png": "png",
        "image/jpeg": "jpg",
        "image/jpg": "jpg",
        "image/gif": "gif",
        "image/bmp": "bmp",
        "image/webp": "webp",
        "image/tiff": "tiff",
        "image/x-icon": "ico",
    }
    if not content_type:
        return "png"
    return mapping.get(content_type.lower().split(";")[0].strip(), "png")


def table_to_html(table_data: List[List[Optional[str]]]) -> str:
    """
    将二维表格数据（pdfplumber extract_tables 风格）转换为 HTML 字符串。
    首行作为表头 <th>，其余 <td>；空 cell 渲染为空字符串。
    """
    if not table_data:
        return ""

    html_parts = ['<table border="1">']
    for i, row in enumerate(table_data):
        tag = "th" if i == 0 else "td"
        cells = "".join(
            f"<{tag}>{(cell if cell else '')}</{tag}>" for cell in row
        )
        html_parts.append("<tr>" + cells + "</tr>")
    html_parts.append("</table>")
    return "".join(html_parts)


# 代码语言启发式关键字（取头部 200 字符判断）
_LANG_KEYWORDS: List[Tuple[str, str]] = [
    ("public class", "java"),
    ("System.out", "java"),
    ("private class", "java"),
    ("def ", "python"),
    ("import ", "python"),
    ("func ", "go"),
    ("fn ", "rust"),
    ("function ", "javascript"),
    ("var ", "javascript"),
    ("const ", "javascript"),
    ("func ", "swift"),
    ("fun ", "kotlin"),
    ("package main", "go"),
    ("#include", "c"),
]


def detect_code_language(code_text: str) -> Optional[str]:
    """
    简单启发式检测代码语言。
    与 parser.py 旧 _detect_language 思路一致，但顺序按关键字特异性排列，
    避免被 "import" 这种通用词过早判定为 python（Java/Go 也有 import）。
    """
    if not code_text:
        return None
    head = code_text[:200]
    # SQL 单独走大写匹配
    upper = head.upper()
    if "SELECT " in upper and " FROM " in upper:
        return "sql"
    for keyword, lang in _LANG_KEYWORDS:
        if keyword in head:
            return lang
    return None
