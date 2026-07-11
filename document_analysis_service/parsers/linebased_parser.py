"""
行式文本解析器（log / csv / tsv / txt）

职责：将行式文本文件解析为统一的 Node JSON 序列。

核心策略 —— 三级降级拆分 + 阈值累加：
1. 强段落分隔（多换行/双换行）拆分，正常段落（≤ threshold*1.5）累加到阈值输出
2. 显著超长段落（> threshold*1.5）：先 flush 当前 buffer，再 splitOversizedParagraph
   - splitOversizedParagraph：有换行则 splitByWeakParagraphs（保持列表项完整），
     所有弱段落都 ≤ threshold 则 accumulateWithThreshold；否则按句子 splitBySentenceWithThreshold 兜底
3. splitByWeakParagraphs：逐行，列表项连续合并为一个块（不被拆散），空行分隔
4. 列表项识别：- / * / + / 数字序号
5. 句子分隔 [。！？.!?；;]，保留分隔符在句末，累加到阈值切

Node JSON 协议（与 Java 侧 NodeDTO 对应）：
- id: str "n1"... 自管递增
- type: "text"
- text: 块文本
- titlePath: None（行式文档无标题路径）
- groupId: str 或 None（超长段落内部拆出的小 Node 共享同一 groupId 标识同源整块；普通块 None）
- page: 1
- bbox: None
- language: None
- html: None
"""

import logging
import re
from pathlib import Path
from typing import List, Optional

logger = logging.getLogger("docling_analysis_service.parsers.linebased_parser")

CHUNK_THRESHOLD = 600

# 强段落分隔：双换行或多换行（独立语义块）
STRONG_PARAGRAPH_SEP = r"\n\s*\n"

# 句子分隔符（中英文）
SENTENCE_DELIMITER = re.compile(r"[。！？.!?；;]")

# 行式文本扩展名
LINE_BASED_EXTENSIONS = {"log", "csv", "tsv", "txt"}


class LineBasedParser:
    """行式文本解析器，基于标准库 re 实现"""

    def __init__(self, chunk_threshold: int = CHUNK_THRESHOLD):
        """
        :param chunk_threshold: 拆分阈值（默认 600，与旧 Java LineBased 一致）
        """
        self.threshold = chunk_threshold
        logger.info(
            "LineBasedParser 初始化完成，阈值: %d", self.threshold
        )

    # ============================== 对外入口 ==============================

    def parse(
        self, file_path: str, document_id: int, user_id: int
    ) -> List[dict]:
        """
        解析行式文本文件，返回 Node JSON 列表

        :param file_path: 文件本地路径
        :param document_id: 文档 ID（保留参数，与 parser.py 风格一致）
        :param user_id: 用户 ID（保留参数，与 parser.py 风格一致）
        :return: Node dict 列表，每个 Node 形如
                 {id, type, text, titlePath, groupId, page, bbox, language, html}
        """
        file_path = Path(file_path)
        logger.info("LineBasedParser 开始解析: %s", file_path)

        try:
            # 行式文本统一 UTF-8（失败则尝试常见编码兜底）
            text = self._read_text(file_path)
        except Exception as e:
            logger.warning("LineBasedParser 读取文件失败: %s, %s", file_path, e)
            return []

        if not text or not text.strip():
            logger.info("LineBasedParser 文件内容为空: %s", file_path)
            return []

        # 三级降级拆分（含 groupId 标注）
        nodes = self._split_with_three_level_strategy(text)

        logger.info(
            "LineBasedParser 解析完成: %s, 共 %d 个 Node", file_path, len(nodes)
        )
        return nodes

    # ============================== 三级降级主流程 ==============================

    def _split_with_three_level_strategy(self, text: str) -> List[dict]:
        """
        三级降级拆分策略：强段落（多换行）→ 弱段落（单换行）→ 句子
        累加机制：从上到下收集并组为不超过阈值的段作为一个 chunk

        与 Java splitWithThreeLevelStrategy 等价，区别在于：
        - 输出的是 Node dict 列表（而非 ChunkResult）
        - 超长段落内部拆出的小 Node 共享同一 groupId（不再输出整块超长镜像父 Node），Java 侧据 groupId 合成父子 chunk
        """
        results: List[dict] = []
        if not text:
            return results

        node_seq = self._make_node_seq()

        # 1. 按强段落分隔（双换行/多换行）拆分
        strong_paragraphs = re.split(STRONG_PARAGRAPH_SEP, text)

        buffer = ""
        for para in strong_paragraphs:
            trimmed_para = para.strip()
            if not trimmed_para:
                continue

            # 2. 检查段落是否显著超长（> threshold * 1.5）
            if len(trimmed_para) > self.threshold * 1.5:
                # 先 flush 当前 buffer
                if buffer:
                    results.append(self._make_text_node(next(node_seq), buffer.strip(), group_id=None))
                    buffer = ""

                # 超长段落：内部拆为多个小 Node，共享同一 groupId（标识同源整块）；
                # 不再输出整块超长的镜像父 Node（Java 侧据 groupId 合成父子 chunk）
                group_id = next(node_seq)
                sub_chunks = self._split_oversized_paragraph(trimmed_para, self.threshold)
                for sub in sub_chunks:
                    if sub and sub.strip():
                        results.append(
                            self._make_text_node(
                                next(node_seq), sub.strip(), group_id=group_id
                            )
                        )
            else:
                # 3. 正常段落：累加到阈值
                add_len = len(trimmed_para) + (2 if buffer else 0)
                if buffer and len(buffer) + add_len > self.threshold:
                    # 超过阈值，先输出当前 buffer
                    results.append(self._make_text_node(next(node_seq), buffer.strip(), group_id=None))
                    buffer = trimmed_para
                else:
                    if buffer:
                        buffer += "\n\n"
                    buffer += trimmed_para

        # 输出剩余 buffer
        if buffer:
            results.append(self._make_text_node(next(node_seq), buffer.strip(), group_id=None))

        return results

    # ============================== 超长段落拆分 ==============================

    def _split_oversized_paragraph(self, para: str, threshold: int) -> List[str]:
        """
        拆分显著超长段落：先尝试按单换行拆分为弱段落，
        无换行或拆分后仍超长则按句子兜底拆分。
        （与 Java splitOversizedParagraph 一致）
        """
        # 1. 尝试按单换行拆分为弱段落
        if "\n" in para:
            weak_paragraphs = self._split_by_weak_paragraphs(para)
            # 检查是否所有弱段落都满足阈值
            all_fit = all(len(p) <= threshold for p in weak_paragraphs)
            if all_fit:
                # 累加到阈值后输出
                return self._accumulate_with_threshold(weak_paragraphs, threshold)

        # 2. 无换行或拆分后仍超长 → 按句子拆分作为兜底
        return self._split_by_sentence_with_threshold(para, threshold)

    def _split_by_weak_paragraphs(self, para: str) -> List[str]:
        """
        按单换行拆分弱段落，保持列表项完整性。
        连续的列表项（- / * / + / 数字序号）合并为一个区块，不被拆散。
        （与 Java splitByWeakParagraphs 一致）
        """
        weak_paragraphs: List[str] = []
        lines = para.split("\n")

        block_buffer = ""
        in_list = False

        for line in lines:
            trimmed_line = line.strip()
            is_item = self._is_list_item(trimmed_line)

            if is_item:
                # 当前行是列表项
                if not in_list and block_buffer:
                    # 之前的非列表内容先输出
                    weak_paragraphs.append(block_buffer.strip())
                    block_buffer = ""
                in_list = True
                if block_buffer:
                    block_buffer += "\n"
                block_buffer += line
            else:
                # 当前行不是列表项
                if in_list and trimmed_line:
                    # 列表结束（遇到非空非列表行），输出列表块
                    if block_buffer:
                        weak_paragraphs.append(block_buffer.strip())
                        block_buffer = ""
                    in_list = False
                if not trimmed_line:
                    # 空行：段落分隔，输出当前块
                    if block_buffer:
                        weak_paragraphs.append(block_buffer.strip())
                        block_buffer = ""
                    in_list = False
                else:
                    # 普通文本行
                    if block_buffer:
                        block_buffer += "\n"
                    block_buffer += line

        # 输出剩余
        if block_buffer:
            weak_paragraphs.append(block_buffer.strip())

        return weak_paragraphs

    def _is_list_item(self, line: str) -> bool:
        """
        判断一行是否为列表项。
        支持无序列表（- / * / +）和有序列表（1. / 2. 等数字序号）。
        （与 Java isListItem 一致）
        """
        if not line:
            return False
        # 无序列表：- * +
        if line.startswith("- ") or line.startswith("* ") or line.startswith("+ "):
            return True
        # 有序列表：1. 2. 等数字序号
        if re.match(r"^\d+\.\s+.+", line):
            return True
        return False

    # ============================== 阈值累加 ==============================

    def _accumulate_with_threshold(self, chunks: List[str], threshold: int) -> List[str]:
        """
        将拆分后的子块累加到阈值后输出。
        子块之间以单换行连接，累加超过阈值则切出当前块。
        （与 Java accumulateWithThreshold 一致）
        """
        results: List[str] = []
        if not chunks:
            return results

        buffer = ""
        for chunk in chunks:
            if not chunk or not chunk.strip():
                continue
            trimmed = chunk.strip()
            # 单换行分隔长度为 1
            add_len = len(trimmed) + (1 if buffer else 0)
            if buffer and len(buffer) + add_len > threshold:
                results.append(buffer.strip())
                buffer = trimmed
            else:
                if buffer:
                    buffer += "\n"
                buffer += trimmed

        if buffer:
            results.append(buffer.strip())

        return results

    # ============================== 句子兜底拆分 ==============================

    def _split_by_sentence_with_threshold(self, text: str, threshold: int) -> List[str]:
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

    # ============================== 内容特征判定（supports 等价） ==============================

    def is_line_based_content(self, text: str) -> bool:
        """
        判断文本是否为行式内容（行长度方差小）。
        （与 Java isLineBasedContent 一致，供路由层 supports 二次判定使用）
        """
        lines = text.split("\n")
        if len(lines) < 5:
            return False

        non_empty_count = 0
        total_len = 0.0
        sum_squared_len = 0.0

        for line in lines:
            trimmed = line.strip()
            if trimmed:
                non_empty_count += 1
                total_len += len(trimmed)
                sum_squared_len += len(trimmed) * len(trimmed)

        if non_empty_count < 3:
            return False

        mean_len = total_len / non_empty_count
        variance = (sum_squared_len / non_empty_count) - (mean_len * mean_len)

        return variance < mean_len * mean_len * 0.5

    def has_frequent_blank_lines(self, text: str) -> bool:
        """
        判断文本是否有频繁的空白行块（>= 2 连续空行）。
        （与 Java hasFrequentBlankLines 一致，供路由层 supports 二次判定使用）
        """
        blank_line_block_count = 0
        in_blank = False
        consecutive_blank = 0

        lines = text.split("\n")
        for line in lines:
            if not line.strip():
                if not in_blank:
                    in_blank = True
                    consecutive_blank = 1
                else:
                    consecutive_blank += 1
            else:
                if in_blank and consecutive_blank >= 2:
                    blank_line_block_count += 1
                in_blank = False
                consecutive_blank = 0

        return blank_line_block_count >= 3

    @staticmethod
    def supports_extension(file_path: str) -> bool:
        """扩展名判定（与 Java LINE_BASED_EXTENSIONS 一致）"""
        suffix = Path(file_path).suffix.lower().lstrip(".")
        return suffix in LINE_BASED_EXTENSIONS

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
        node_id: str, text: str, group_id: Optional[str]
    ) -> dict:
        """
        构造一个 text Node dict（与 Java 侧 NodeDTO 协议对应）。
        行式文档无标题路径、无页面/bbox/html/language 信息。
        group_id 非空表示该 Node 属于「同源整块」拆出的小 Node（超长段落语义切分产物），
        Java 侧据此聚同组 Node 优先组装并合成父子 chunk；普通块 group_id=None。
        """
        return {
            "id": node_id,
            "type": "text",
            "text": text,
            "titlePath": None,
            "groupId": group_id,
            "page": 1,
            "bbox": None,
            "language": None,
            "html": None,
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
            "LineBasedParser 无法以常见编码解码 %s，回退 latin-1: %s",
            file_path, last_err,
        )
        with open(file_path, "r", encoding="latin-1") as f:
            return f.read()
