"""
HTML 解析器

职责：将 HTML 文档解析为统一的 Node JSON 序列。

选型决策：
- 使用 beautifulsoup4（>=4.15.0），默认 html.parser 后端，不引入 lxml。
- bs4 负责 DOM 遍历与 script/style 过滤；titlePath 栈推导、超长拆分、groupId 标注
  仍是本系统手写领域逻辑（标准库 re）。

核心策略（复刻旧 Java HtmlChunkStrategy）：
1. BeautifulSoup 解析 DOM，decompose() 掉 script/style/noscript/head/meta/link
2. 从 body（无 body 用整棵 soup）深度优先遍历：
   - h1-h6：先 flushBuffer 输出累积文本为 block，再更新标题栈
     （弹出 size>=level 的，压入标题文本），标题文本也加入 buffer
   - section/article：flushBuffer，记录栈大小，递归遍历，遍历完恢复栈并 flushBuffer
   - table：flushBuffer，作为原子块输出 type="table" 的 block（html 字段），不递归
   - 其他元素：递归遍历，文本节点累加到 buffer
3. flushBuffer：buffer trim 非空输出为 block，titlePath = 标题栈 join " > "（空则 None）
4. 超长 block（> CHUNK_THRESHOLD=1000）：内部按句子 [。！？.!?；;] 拆分累加到阈值为多个小 Node，共享同一 groupId（同源整块），不再输出整块超长镜像父 Node
5. 若解析后只有 0-1 个块，fallback：取纯文本 soup.get_text()，按段落/句子拆分

Node JSON 协议（与 Java 侧 NodeDTO 对应）：
- id: str "n1","n2"... 自管递增
- type: "text"（HTML 块统一为 text；table 转 html 字段并 type="table"）
- text: 块文本（table 时为 None）
- titlePath: str 标题路径，无则 None
- groupId: str 或 None（超长 block 内部拆出的小 Node 共享同一 groupId；普通块 None）
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

logger = logging.getLogger("docling_analysis_service.parsers.html_parser")

CHUNK_THRESHOLD = 1000

SENTENCE_DELIMITER = re.compile(r"[。！？.!?；;]")

# 强段落分隔：多换行/双换行（fallback 用）
STRONG_PARAGRAPH_SEP = r"\n\s*\n"

# HTML 扩展名
HTML_EXTENSIONS = {"html", "htm"}

_STRIP_TAGS = ("script", "style", "noscript", "head", "meta", "link")


class HtmlParser:
    """HTML 解析器，基于 BeautifulSoup + 手写领域逻辑。

    bs4 负责 DOM 遍历与脚本过滤；标题栈、超长拆分、groupId 标注为本系统手写逻辑。
    """

    def __init__(self, chunk_threshold: int = CHUNK_THRESHOLD):
        """
        :param chunk_threshold: 拆分阈值（默认 1000，与旧 Java HtmlChunkStrategy 一致）
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
        return self._blocks_to_nodes(blocks, node_seq)

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
        - section/article：flushBuffer → 记录栈大小 → 递归 → 恢复栈 → flushBuffer
        - table：flushBuffer → 原子块输出 type="table"（不递归）
        - 其他元素：递归遍历
        - 文本节点：累加到 buffer

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

            elif tag in ("section", "article"):
                # section/article：flush → 记录栈大小 → 递归 → 恢复栈 → flush
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
                # 其他元素：递归遍历
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
        - text block ≤ threshold → 单个 text Node（groupId=None）
        - text block > threshold → 内部按句子拆为多个小 text Node，共享同一 groupId（同源整块，不再输出整块超长镜像父 Node）
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

            if len(text) <= self.threshold:
                # 普通块
                results.append(
                    self._make_text_node(
                        next(node_seq), text, title_path=title_path, group_id=None
                    )
                )
            else:
                # 超长块：内部拆句子为多个小 Node，共享同一 groupId
                group_id = next(node_seq)
                sub_chunks = self._split_by_sentence_with_threshold(
                    text, self.threshold
                )
                for sub_text in sub_chunks:
                    sub_text = sub_text.strip()
                    if sub_text:
                        results.append(
                            self._make_text_node(
                                next(node_seq),
                                sub_text,
                                title_path=title_path,
                                group_id=group_id,
                            )
                        )

        return results

    # ============================== fallback 拆分 ==============================

    def _fallback_split(self, plain_text: str) -> List[dict]:
        """
        DOM 遍历产出 0-1 块时的 fallback：取纯文本按段落（双换行）拆分，
        段落累加到阈值输出，超长段落整体作为一个 block（交由 _blocks_to_nodes 拆句子）。

        （与 Java fallbackSplit 等价，区别在于保留段落换行用于切分边界）
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
                # 超长段落：先 flush buffer，再整体作为一个 block（后续 _blocks_to_nodes 会拆句子）
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

    # ============================== 句子拆分 ==============================

    def _split_by_sentence_with_threshold(
        self, text: str, threshold: int
    ) -> List[str]:
        """
        按句子分隔符拆分文本，累加句子直到最接近阈值，不切断单个句子。
        句子是原子单位，不会被截断。
        （与 Java splitBySentenceWithThreshold 一致）
        """
        results: List[str] = []
        if not text or not text.strip():
            return results

        sentences = self._split_into_sentences(text)

        buffer = ""
        for sentence in sentences:
            if not sentence.strip():
                continue
            # 检查累加后是否超过阈值
            add_len = len(sentence)
            if buffer and len(buffer) + add_len > threshold:
                # 超过阈值，先输出当前 buffer
                results.append(buffer.strip())
                buffer = sentence
            else:
                buffer += sentence

        # 输出剩余 buffer
        if buffer:
            results.append(buffer.strip())

        return results

    def _split_into_sentences(self, text: str) -> List[str]:
        """
        按句子分隔符拆分文本，保留分隔符在句子末尾。
        （与 Java splitIntoSentences 一致）
        """
        sentences: List[str] = []
        if not text:
            return sentences

        last_end = 0
        for m in SENTENCE_DELIMITER.finditer(text):
            delimiter_end = m.end()
            sentence = text[last_end:delimiter_end]
            if sentence.strip():
                sentences.append(sentence)
            last_end = delimiter_end

        # 剩余部分（无分隔符的结尾）
        if last_end < len(text):
            remaining = text[last_end:]
            if remaining.strip():
                sentences.append(remaining)

        return sentences

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
