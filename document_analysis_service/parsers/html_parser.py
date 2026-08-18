"""
HTML 解析器

职责：将 HTML 文档解析为统一的 Node JSON 序列。

选型决策：
- 使用 beautifulsoup4（>=4.15.0），默认 html.parser 后端，不引入 lxml。
- bs4 负责 DOM 遍历与 script/style 过滤；titlePath 栈推导、语义容器边界识别
  仍是本系统手写领域逻辑（标准库 re）。

核心策略：
1. BeautifulSoup 解析 DOM，decompose() 掉 script/style/noscript/head/meta/link
2. 从 body（无 body 用整棵 soup）深度优先遍历：
   - h1-h6：先 flushBuffer 输出累积文本为 block，再更新标题栈
     （弹出 size>=level 的，压入标题文本），标题文本也加入 buffer
   - 语义容器（section/article/main/header/footer/nav/aside/figure/form）：
     flushBuffer，记录栈大小，递归遍历，遍历完恢复栈并 flushBuffer。
     Node 边界由 HTML5 语义容器决定，而非 DOM 标签数量或文本长度。
   - table：flushBuffer，作为原子块输出 type="table" 的 block（html 字段），不递归
   - 其他元素（div/span 等布局标签）：仅递归遍历，忽略其边界，文本节点累加到 buffer
3. flushBuffer：buffer trim 非空输出为 block，titlePath = 标题栈 join " > "（空则 None）
4. text block 保持原子性：无论是否超长均作为单个 text Node 输出（groupId=None），
   不再按句子拆分。html 文本块不引入 LLM 解读（本次保持原文向量化），
   超长文本块由 Java 侧 NodeBasedChunkBuilder 独立成块。
5. 若解析后只有 0-1 个块，fallback：取纯文本 soup.get_text()，按段落拆分

Node JSON 协议（与 Java 侧 NodeDTO 对应）：
- id: str "n1","n2"... 自管递增
- type: "text"（HTML 块统一为 text；table 转 html 字段并 type="table"）
- text: 块文本（table 时为 None）
- titlePath: str 标题路径，无则 None
- groupId: 始终 None（文本块原子化后不再产生超长块内部拆分；保留字段以兼容协议）
- page: 1
- bbox: None
- language: None
- html: None（除非识别到 table 则转 HTML 字符串）
"""

import logging
import re
from pathlib import Path
from typing import List, Optional

from bs4 import BeautifulSoup, NavigableString, Tag

from parsers._common import cap_text_nodes

logger = logging.getLogger("docling_analysis_service.parsers.html_parser")

CHUNK_THRESHOLD = 250

# 强段落分隔：多换行/双换行（fallback 用）
STRONG_PARAGRAPH_SEP = r"\n\s*\n"

# HTML 扩展名
HTML_EXTENSIONS = {"html", "htm"}

_STRIP_TAGS = ("script", "style", "noscript", "head", "meta", "link")

# HTML5 语义容器：如下的标签作为Node的天然边界
_SEMANTIC_CONTAINER_TAGS = (
    "section", "article", "main", "header", "footer",
    "nav", "aside", "figure", "form",
)


class HtmlParser:
    """HTML 解析器，基于 BeautifulSoup + 手写领域逻辑。

    bs4 负责 DOM 遍历与脚本过滤；标题栈、超长拆分、groupId 标注为本系统手写逻辑。
    """

    def __init__(self, chunk_threshold: int = CHUNK_THRESHOLD):
        """
        :param chunk_threshold: 阈值（文本块原子化后仅用于 fallback 段落累加上限，
            不再驱动超长块内部句子拆分）
        """
        self.threshold = chunk_threshold
        logger.info("HtmlParser 初始化完成，阈值: %d", self.threshold)

    # ============================== 对外入口 ==============================

    def parse(
        self, file_path: str, document_id: int, user_id: int ##document_id、user_id貌似并没有被使用到
    ) -> List[dict]:
        """
        解析 HTML 文件，返回 Node JSON 列表

        :param file_path: 文件本地路径
        :param document_id: 文档 ID（保留参数，与 parser.py 风格一致）
        :param user_id: 用户 ID（保留参数，与 parser.py 风格一致）
        :return: Node dict 列表，每个 Node 形如
                 {id, type, text, titlePath, groupId, page, bbox, language, html}
        """
        file_path = Path(file_path)
        logger.info("HtmlParser 开始解析: %s", file_path)

        try:
            text = self._read_text(file_path)
        except Exception as e:
            logger.warning("HtmlParser 读取文件失败: %s, %s", file_path, e)
            return []

        if not text or not text.strip():
            logger.info("HtmlParser 文件内容为空: %s", file_path)
            return []

        # 核心解析流程
        nodes = self._parse_html(text)

        logger.info(
            "HtmlParser 解析完成: %s, 共 %d 个 Node", file_path, len(nodes)
        )
        return nodes

    # ============================== 核心解析流程 ==============================

    def _parse_html(self, text: str) -> List[dict]:
        """
        核心 HTML 解析流程：DOM 遍历 → blocks → Node。

        策略：
        1. BeautifulSoup 解析，decompose 掉 script/style/noscript/head/meta/link
        2. 从 body（无 body 用 soup）深度优先遍历，产出 blocks
        3. 若 blocks <= 1，fallback 取纯文本按段落/句子拆分
        4. blocks → Node（超长 block 内部拆句子 + 共享 groupId）
        """
        soup = BeautifulSoup(text, "html.parser")

        # 移除 script/style/noscript/head/meta/link
        for bad in soup.find_all(list(_STRIP_TAGS)):
            bad.decompose()

        # 从 body 开始遍历（无 body 用整棵 soup，适配 HTML 片段）
        start = soup.body if soup.body is not None else soup

        blocks: List[dict] = []
        title_stack: List[str] = []
        buffer: List[str] = []

        self._walk_dom(start, blocks, title_stack, buffer)
        self._flush_buffer(buffer, blocks, title_stack)

        # fallback：解析后只有 0-1 个块，取纯文本按段落/句子拆分
        if len(blocks) <= 1:
            plain_text = soup.get_text(separator="\n")
            blocks = self._fallback_split(plain_text)
            logger.info(
                "HtmlParser DOM 遍历产出 %d 块，触发 fallback，重新拆分得到 %d 块",
                1 if blocks else 0,
                len(blocks),
            )

        # blocks → Node
        node_seq = self._make_node_seq()
        nodes = self._blocks_to_nodes(blocks, node_seq)

        # Node 字数兜底：超长 text Node 拆为多个小 Node（共享 groupId），不截断内容
        # （HTML 文本块原本保持原子性，兜底拆分使其与其它解析器一致：超长文本拆为同源小 Node）
        nodes = cap_text_nodes(nodes)

        return nodes

    # ============================== DOM 深度优先遍历 ==============================

    def _walk_dom(
        self,
        element,
        blocks: List[dict],
        title_stack: List[str],
        buffer: List[str],
    ) -> None:
        """
        深度优先遍历 DOM（与 Java walkDom 等价）。

        - h1-h6：flushBuffer → 更新标题栈 → 标题文本加 buffer
        - 语义容器（section/article/main/header/footer/nav/aside/figure/form）：
          flush → 记录栈大小 → 递归 → 恢复栈 → flush
        - table：flush → 原子块输出 type="table"（不递归）
        - 其他元素（div/span 等布局标签）：仅递归遍历，忽略其边界
        - 文本节点：累加到 buffer

        语义容器扩展（some.md 第16点第四小点，GPTinHTMLNodes.md 第一层）：
        Node 边界由 HTML5 语义容器决定，而非 DOM 标签数量或文本长度。
        form/nav/aside/header/footer/main/figure 与 section/article 同等作为天然边界。
        div/span 等仅承担布局/样式作用的标签完全忽略边界，仅递归提取内容。

        :param element: bs4 元素（Tag 或 soup）
        :param blocks: 累积的 block 列表（输出）
        :param title_stack: 标题栈（list 模拟 Deque）
        :param buffer: 文本缓冲区（list 模拟 StringBuilder）
        """
        for child in element.children:
            # 仅处理普通文本节点（排除 Comment/Doctype/CData 等 NavigableString 子类）
            if type(child) is NavigableString:
                buffer.append(str(child))
                continue

            if not isinstance(child, Tag):
                # Comment / Doctype / ProcessingInstruction 等跳过
                continue

            tag = child.name.lower() if child.name else ""

            # 防御性：script/style/noscript 已 decompose，这里再兜底跳过
            if tag in ("script", "style", "noscript"):
                continue

            if tag in ("h1", "h2", "h3", "h4", "h5", "h6"):
                # 标题：先 flush 当前 buffer
                self._flush_buffer(buffer, blocks, title_stack)

                level = int(tag[1])
                # 标题文本（Jsoup el.text() 等价：合并后代文本并归一化空白）
                heading_text = " ".join(child.get_text().split())
                if heading_text:
                    # 弹出 size >= level 的（与 Java while titleStack.size() >= level pollLast 一致）
                    while len(title_stack) >= level:
                        title_stack.pop()
                    title_stack.append(heading_text)
                # 标题文本加入 buffer（与 Java buffer.append(headingText).append(" ") 一致）
                buffer.append(heading_text)
                buffer.append(" ")

            elif tag in _SEMANTIC_CONTAINER_TAGS:
                # 语义容器：flush → 记录栈大小 → 递归 → 恢复栈 → flush
                # 容器内部的子标题仅在该容器作用域内有效，递归返回后恢复栈，避免污染兄弟块
                self._flush_buffer(buffer, blocks, title_stack)
                saved_size = len(title_stack)

                self._walk_dom(child, blocks, title_stack, buffer)

                # 恢复栈到 saved_size
                while len(title_stack) > saved_size:
                    title_stack.pop()
                self._flush_buffer(buffer, blocks, title_stack)

            elif tag == "table":
                # 表格作为原子块：flush → 输出 type="table" block（不递归）
                self._flush_buffer(buffer, blocks, title_stack)
                title_path = " > ".join(title_stack) if title_stack else None
                blocks.append({
                    "text": None,
                    "titlePath": title_path,
                    "type": "table",
                    "html": str(child),
                })

            else:
                # 其他元素（div/span 等布局标签）：仅递归遍历，忽略其边界
                self._walk_dom(child, blocks, title_stack, buffer)

    def _flush_buffer(
        self,
        buffer: List[str],
        blocks: List[dict],
        title_stack: List[str],
    ) -> None:
        """
        将 buffer trim 后非空则输出为 text block，titlePath = 标题栈 join " > "（空则 None）。
        （与 Java flushBuffer 等价）
        """
        text = "".join(buffer).strip()
        if text:
            title_path = " > ".join(title_stack) if title_stack else None
            blocks.append({
                "text": text,
                "titlePath": title_path,
                "type": "text",
                "html": None,
            })
        buffer.clear()

    # ============================== blocks → Node ==============================

    def _blocks_to_nodes(self, blocks: List[dict], node_seq) -> List[dict]:
        """
        将 blocks 转换为 Node JSON 列表。

        - table block → table Node（原子，不拆分）
        - text block → 单个 text Node（groupId=None），无论是否超长均保持原子性

        改造点（some.md 第16点，与 code 阶段一对齐）：
        text block 不再按句子拆分 + groupId。html 文本块不引入 LLM 解读（本次保持原文向量化），
        超长文本块直接作为单个 Node 输出，Java 侧 NodeBasedChunkBuilder 对超大单 Node 独立成块。
        """
        results: List[dict] = []
        for block in blocks:
            btype = block.get("type", "text")
            title_path = block.get("titlePath")

            if btype == "table":
                # 表格：原子块，整体输出
                results.append(
                    self._make_table_node(
                        next(node_seq),
                        html=block.get("html") or "",
                        title_path=title_path,
                        group_id=None,
                    )
                )
                continue

            text = (block.get("text") or "").strip()
            if not text:
                continue

            # 文本块保持原子性：无论是否超长均作为单个 text Node 输出
            results.append(
                self._make_text_node(
                    next(node_seq), text, title_path=title_path, group_id=None
                )
            )

        return results

    # ============================== fallback 拆分 ==============================

    def _fallback_split(self, plain_text: str) -> List[dict]:
        """
        DOM 遍历产出 0-1 块时的 fallback：取纯文本按段落（双换行）拆分，
        段落累加到阈值输出，超长段落整体作为一个 block。

        改造点：文本块原子化后不再按句子拆分，超长段落整体作为一个 block，
        由 _blocks_to_nodes 作为单个原子 text Node 输出。
        """
        blocks: List[dict] = []
        if not plain_text or not plain_text.strip():
            return blocks

        paragraphs = re.split(STRONG_PARAGRAPH_SEP, plain_text)

        buffer = ""
        for para in paragraphs:
            trimmed = para.strip()
            if not trimmed:
                continue

            if len(trimmed) > self.threshold:
                # 超长段落：先 flush buffer，再整体作为一个 block（原子化，不再拆句子）
                if buffer:
                    blocks.append({
                        "text": buffer.strip(),
                        "titlePath": None,
                        "type": "text",
                        "html": None,
                    })
                    buffer = ""
                blocks.append({
                    "text": trimmed,
                    "titlePath": None,
                    "type": "text",
                    "html": None,
                })
            else:
                # 正常段落：累加到阈值
                add_len = len(trimmed) + (1 if buffer else 0)
                if buffer and len(buffer) + add_len > self.threshold:
                    blocks.append({
                        "text": buffer.strip(),
                        "titlePath": None,
                        "type": "text",
                        "html": None,
                    })
                    buffer = trimmed
                else:
                    if buffer:
                        buffer += "\n"
                    buffer += trimmed

        if buffer:
            blocks.append({
                "text": buffer.strip(),
                "titlePath": None,
                "type": "text",
                "html": None,
            })

        return blocks

    # ============================== Node 构造辅助 ==============================

    @staticmethod
    def _make_node_seq():
        """自管递增 Node id 生成器：n1, n2, ..."""
        i = 0
        while True:
            i += 1
            yield f"n{i}"

    @staticmethod
    def _make_text_node(
        node_id: str, text: str, title_path: Optional[str], group_id: Optional[str]
    ) -> dict:
        """构造一个 text Node dict（与 Java 侧 NodeDTO 协议对应）。
        group_id 非空表示同源整块拆出的子 Node（共享同 groupId），普通块 None。"""
        return {
            "id": node_id,
            "type": "text",
            "text": text,
            "titlePath": title_path,
            "groupId": group_id,
            "page": 1,
            "bbox": None,
            "language": None,
            "html": None,
        }

    @staticmethod
    def _make_table_node(
        node_id: str, html: str, title_path: Optional[str], group_id: Optional[str]
    ) -> dict:
        """构造一个 table Node dict（html 字段存放 HTML 字符串）。
        group_id 非空表示同源整块拆出的子 Node，普通块 None。"""
        return {
            "id": node_id,
            "type": "table",
            "text": None,
            "titlePath": title_path,
            "groupId": group_id,
            "page": 1,
            "bbox": None,
            "language": None,
            "html": html,
        }

    # ============================== 文件读取 ==============================

    @staticmethod
    def _read_text(file_path: Path) -> str:
        """读取文本文件，优先 UTF-8，失败则尝试常见编码兜底"""
        encodings = ["utf-8", "utf-8-sig", "gbk", "gb18030", "latin-1"]
        last_err: Optional[Exception] = None
        for enc in encodings:
            try:
                with open(file_path, "r", encoding=enc) as f:
                    return f.read()
            except UnicodeDecodeError as e:
                last_err = e
                continue
        # 所有编码都失败，最后用 latin-1 强读（不会抛 UnicodeDecodeError）
        logger.warning(
            "HtmlParser 无法以常见编码解码 %s，回退 latin-1: %s",
            file_path, last_err,
        )
        with open(file_path, "r", encoding="latin-1") as f:
            return f.read()

    # ============================== 内容特征判定（supports 等价） ==============================

    @staticmethod
    def supports_extension(file_path: str) -> bool:
        """扩展名判定（与 Java HtmlChunkStrategy.supports 一致）"""
        suffix = Path(file_path).suffix.lower().lstrip(".")
        return suffix in HTML_EXTENSIONS
