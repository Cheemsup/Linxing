"""
parsers 子包共享工具模块

仅收录各解析器共同需要的小工具（节点 id 生成器、标题路径构建、哈希、表格 HTML、
语言检测、累加阈值弹性策略），避免在各 parser 间重复实现。

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


# ============================== 累加阈值弹性策略 ==============================
# 累加阈值采用弹性区间，减少在阈值附近对强关联语义的人为切断。
# 语义：仅当"加入下一块后会超过弹性上界"时才切出当前累加块；落在弹性区间内的累积都视为可接受。
# 仅作用于"同一原文流累加"场景（弱段落累加 / 句子累加），不改变跨强段落/标题的硬边界。
ELASTIC_RATIO = 0.2

# 图片预估字数：图片本身无文本，但其 VLM 语义增强后通常产出 50~200 字描述。
# 在 Python 解析阶段 VLM 尚未运行，图片的真实语义字数不可知，故以中位数偏稳的 120 字预估，
# 让图片作为一个"虚拟文本块"参与前后文本累加的 flush 判断，避免"一遇到图片就无条件截断文本聚类"
# 把本该连续的文本 prematurely 切碎（尤其超长段被拆成 groupId 子块后跨 chunk 隔离）。
# 取值依据：120 字相对 CHUNK_THRESHOLD=1000 占 12%，单张图不会撑爆预算，
# 连续 8~9 张图无文本时才会自然触发切出，符合直觉。Java 装箱阶段用的是 VLM 增强后的真实字数，与此预估无关。
IMAGE_ESTIMATED_CHARS = 120


def elastic_upper_bound(threshold: int, ratio: float = ELASTIC_RATIO) -> int:
    """弹性上界：累加长度超过此值才切出当前块。"""
    return int(threshold * (1.0 + ratio))


def should_flush_under_elastic(
    buffer_len: int, add_len: int, threshold: int, ratio: float = ELASTIC_RATIO
) -> bool:
    """判断是否应在加入下一块前 flush 当前累加缓冲。

    硬阈值策略下：buffer + add > threshold 即 flush。
    弹性策略下：仅当 buffer + add > 弹性上界才 flush；
    落在 [threshold, 弹性上界] 区间的累积不切，保留强关联语义连续性。
    buffer 为空时不 flush（避免首块即被空判误切）。
    """
    if buffer_len == 0:
        return False
    return buffer_len + add_len > elastic_upper_bound(threshold, ratio)


# ============================== Node 字数上限 ==============================
# Java 侧 embedding 输入上限 450 token，按换算关系 token ≈ 1.5 × 中文字符，
# 450 token ≈ 300 中文字符。各解析器产出的 text Node 不应超过 MAX_NODE_CHARS：
# 超长内容在解析期拆为多个小 Node（共享 groupId），而非输出整块超长 Node。
MAX_NODE_CHARS = 300

# 正文/段落累加阈值默认值：弹性上界 = 阈值 × 1.2 ≈ MAX_NODE_CHARS，
# 让正常段落累加在 300 字附近自然切出（见 elastic_upper_bound / should_flush_under_elastic）。
NODE_TARGET_CHARS = 250


def split_long_piece(text: str, max_chars: int = MAX_NODE_CHARS) -> List[str]:
    """将单个超长片段（如无标点的长句）拆为 ≤ max_chars 的多个片段。

    优先在标点/空白处切分避免生硬切断；无边界时字符兜底。
    仅用于「单句本身超长」的兜底场景——正常段落由 sentence 累加在阈值处自然切分。
    返回全部非空片段（不丢内容，不截断）。
    """
    if not text:
        return []
    if len(text) <= max_chars:
        return [text]

    # 常用中英文切分边界
    boundary = re.compile(r"[，。、；！？；,.;:!?（）()\s]")
    pieces: List[str] = []
    cur = text
    while len(cur) > max_chars:
        window = cur[:max_chars]
        # 从后向前找最近边界，避免切断完整词/句
        cut = -1
        for m in boundary.finditer(window):
            cut = m.end()
        if cut <= 0:
            cut = max_chars  # 无边界，字符兜底
        piece = cur[:cut].strip()
        if piece:
            pieces.append(piece)
        cur = cur[cut:].lstrip()

    tail = cur.strip()
    if tail:
        pieces.append(tail)
    return pieces


def cap_text_nodes(nodes: List[dict], max_chars: int = MAX_NODE_CHARS) -> List[dict]:
    """后置 Node 字数兜底：将超长 text Node 拆为多个小 Node（共享 groupId），
    保证单个 text Node 不超过 MAX_NODE_CHARS。

    仅作用于 type == "text" 的 Node；heading/code/table/image/formula 保持原子
    （其 embedding 走语义增强文本，长度已由提示词约束，无超长风险）。
    拆分依据 split_long_piece（优先标点/空白边界，不丢内容、不截断）：
    - 已有 groupId 的 Node 沿用原 groupId（同源整块，Java 侧据 groupId 合成父子 chunk）
    - 无 groupId 的超长 Node 生成新 groupId，标识「同源整块拆出」，与其它解析器
      超长拆分语义一致，保证 small2big 父块还原不失效。
    """
    if max_chars <= 0:
        return nodes

    out: List[dict] = []
    for node in nodes:
        if node.get("type") != "text":
            out.append(node)
            continue
        text = (node.get("text") or "").strip()
        if len(text) <= max_chars:
            out.append(node)
            continue

        pieces = split_long_piece(text, max_chars)
        group_id = node.get("groupId") or f"g-{node.get('id', 'n')}"
        for i, piece in enumerate(pieces):
            piece_node = dict(node)
            piece_node["id"] = f"{node.get('id', 'n')}-{i}"
            piece_node["text"] = piece
            piece_node["groupId"] = group_id
            out.append(piece_node)
    return out


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
