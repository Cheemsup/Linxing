"""
Markdown 解析器

职责：将 Markdown 文档解析为统一的 Node JSON 序列。

选型决策（reference/TODOS/betterRAG/0702_dealWithOldStrategy.md 第 7.3 节）：
- 使用 mistune 3（mistune>=3.3.2）做结构识别（heading level、paragraph 边界、list/list_item 边界、code_block fence 边界、table 边界）。
- mistune 只负责"识别结构边界"，**titlePath 栈推导、超长拆分、groupId 标注、无标题三级降级**仍是本系统手写领域逻辑（用标准库 re）。

核心策略：
1. 按标题拆分（只识别 #{1,3} 一二三级），维护标题栈推导 titlePath（格式 "一级 > 二级 > 三级"），清下级
2. 超长 section（> threshold，默认 1000）在内部按句子拆分为多个小 Node（中英文标点 [。！？.!?；;]），共享同一 groupId（同源整块），不再返回整块超长 Node
3. 无标题文档三级降级：强段落（多换行/双换行）→ 弱段落（单换行，保持列表项完整）→ 句子，阈值累加
4. 标题前有 preamble（前置文本）也作为 section
5. code_block / table 作为原子块不可拆（即使超长也整体输出，不拆句子）

Node JSON 协议（与 Java 侧 NodeDTO 对应）：
- id: str "n1"... 自管递增
- type: "heading" | "text" | "code" | "table" | "image" | "formula"
- text: 文本内容（heading/text/code/formula 用）
- level: int（heading 用，1-3）
- language: str（code 用，可 None）
- html: str（table 用，转 HTML 字符串）
- titlePath: str（标题路径如 "第一章 > 第一节"，非标题块也带其所属标题路径；无标题上下文时 None）
- groupId: str 或 None（超长 section 内部拆出的小 Node 共享同一 groupId 标识同源整块；普通块 None）
- page: int（Markdown 无分页，固定 1）
- bbox: None
"""

import logging
import re
from pathlib import Path
from typing import List, Optional, Dict, Any

from parsers._common import (
    IMAGE_ESTIMATED_CHARS,
    compute_hash,
    should_flush_under_elastic,
)

logger = logging.getLogger("docling_analysis_service.parsers.markdown_parser")

try:
    import mistune
    # 检查版本：需要 mistune 3.x，旧版（0.x/1.x/2.x）API 不兼容
    _mistune_version = getattr(mistune, "__version__", "0.0.0")
    _mistune_major = int(_mistune_version.split(".")[0])
    if _mistune_major < 3:
        logger.warning(
            "mistune 版本过低 (%s)，需要 >=3.3.2，降级为纯正则模式。"
            "请运行 pip install --upgrade mistune>=3.3.2",
            _mistune_version,
        )
        mistune = None  # type: ignore
except ImportError:
    mistune = None  # type: ignore

CHUNK_THRESHOLD = 1000

# 只识别一二三级标题
HEADING_PATTERN = re.compile(r"^(#{1,3})\s+(.+)$", re.MULTILINE)

# 句子分隔符
SENTENCE_DELIMITER = re.compile(r"[。！？.!?；;]")

# 强段落分隔：多换行/双换行
STRONG_PARAGRAPH_SEP = r"\n\s*\n"

# Markdown 图片语法正则：![alt](url)，用于统计段落内图片数（预估语义字数参与 flush 判断）
# 与 _emit_text_with_images 内联正则保持一致
IMAGE_PATTERN = re.compile(r"!\[([^\]]*)\]\(([^)]+)\)")

# Markdown 扩展名
MARKDOWN_EXTENSIONS = {"md", "markdown"}

# mistune table plugin（GFM 表格支持）
try:
    from mistune.plugins import plugin_table
    _TABLE_PLUGIN = plugin_table
except ImportError:
    _TABLE_PLUGIN = None  # type: ignore


class MarkdownParser:
    """Markdown 解析器，基于 mistune 3 AST + 手写领域逻辑。

    mistune 产出的 block token 是扁平列表（heading 与其后段落是兄弟），
    本类用栈推导 titlePath，并处理超长拆分、groupId 标注、无标题三级降级。
    """

    def __init__(
        self,
        image_store_dir: str,
        image_url_prefix: str = "/chunk_images",
        chunk_threshold: int = CHUNK_THRESHOLD,
    ):
        """
        :param image_store_dir: 图片存储根目录（与 docx/pdf 一致，落盘到 {dir}/{userId}/{docId}/）
        :param image_url_prefix: 图片 URL 前缀（与 docx/pdf 一致）
        :param chunk_threshold: 拆分阈值（默认 1000）
        """
        self.image_store_dir = Path(image_store_dir)
        self.image_url_prefix = image_url_prefix.rstrip("/")
        self.threshold = chunk_threshold
        if mistune is None:
            logger.warning(
                "mistune 未安装，MarkdownParser 将降级为纯正则模式（不推荐）。"
                "请运行 pip install mistune>=3.3.2"
            )
        else:
            logger.info(
                "MarkdownParser 初始化完成，mistune 版本: %s, 阈值: %d, 图片存储目录: %s",
                mistune.__version__ if hasattr(mistune, "__version__") else "unknown",
                self.threshold,
                self.image_store_dir,
            )

    # ============================== 对外入口 ==============================

    def parse(
        self, file_path: str, document_id: int, user_id: int
    ) -> List[dict]:
        """
        解析 Markdown 文件，返回 Node JSON 列表

        :param file_path: 文件本地路径
        :param document_id: 文档 ID（保留参数，与 parser.py 风格一致）
        :param user_id: 用户 ID（保留参数，与 parser.py 风格一致）
        :return: Node dict 列表，每个 Node 形如
                 {id, type, text, level, language, html, titlePath, groupId, page, bbox}
        """
        file_path = Path(file_path)
        logger.info("MarkdownParser 开始解析: %s", file_path)

        try:
            text = self._read_text(file_path)
        except Exception as e:
            logger.warning("MarkdownParser 读取文件失败: %s, %s", file_path, e)
            return []

        if not text or not text.strip():
            logger.info("MarkdownParser 文件内容为空: %s", file_path)
            return []

        # 图片输出目录（与 docx/pdf 一致，按 userId/documentId 隔离）
        image_output_dir = self.image_store_dir / str(user_id) / str(document_id)
        image_output_dir.mkdir(parents=True, exist_ok=True)

        # 核心解析流程
        nodes = self._parse_markdown(text, file_path, image_output_dir, user_id, document_id)

        logger.info(
            "MarkdownParser 解析完成: %s, 共 %d 个 Node", file_path, len(nodes)
        )
        return nodes

    # ============================== 核心解析流程 ==============================

    def _parse_markdown(self, text: str, file_path: Path, image_output_dir: Path, user_id: int, document_id: int) -> List[dict]:
        """
        核心 Markdown 解析流程。

        策略：
        1. 先检查是否有标题（HEADING_PATTERN）
        2. 有标题 → 按标题拆分 + titlePath 栈推导 + 超长拆分
        3. 无标题 → 三级降级拆分（强段落 → 弱段落 → 句子）

        图片处理：mistune AST 识别 image token（含 paragraph 内嵌 image），
        仅处理文档自带本地图片资源（相对/绝对路径），远程 http(s) 链接跳过。
        遇图片先 flush 当前段落文本，再产出独立 image Node（与 docx 一致）。
        """
        results: List[dict] = []
        if not text:
            return results

        node_seq = self._make_node_seq()
        img_ctx = {
            "file_path": file_path,
            "image_output_dir": image_output_dir,
            "user_id": user_id,
            "document_id": document_id,
        }

        # 1. 检查是否有标题
        heading_matches = list(HEADING_PATTERN.finditer(text))

        if not heading_matches:
            # 无标题文档：三级降级拆分（与 Java processNoTitleDocument 一致）
            return self._process_no_title_document(text, node_seq, img_ctx)

        # 2. 有标题：按标题拆分（与 Java splitByHeadings 一致）
        # 处理第一个标题前的 preamble（前置文本）
        first_heading_start = heading_matches[0].start()
        if first_heading_start > 0:
            preamble = text[:first_heading_start].strip()
            if preamble:
                self._emit_text_with_images(
                    preamble, None, node_seq, results, img_ctx,
                )

        # 只跟踪一二三级标题（最多三层）
        title_stack: List[Optional[str]] = [None, None, None]  # level 1, 2, 3

        for i, m in enumerate(heading_matches):
            level = len(m.group(1))  # # 的数量
            heading_text = m.group(2).strip()

            # 更新标题栈（level 为 1-3）
            title_stack[level - 1] = heading_text
            # 清空当前级别以下的标题
            for j in range(level, 3):
                title_stack[j] = None

            # 构建 titlePath
            title_path = self._build_title_path(title_stack)

            # 计算内容范围
            content_start = m.end()
            content_end = (
                heading_matches[i + 1].start() if i + 1 < len(heading_matches) else len(text)
            )
            content = text[content_start:content_end].strip()

            # 空标题区块：跳过不生成独立 section，但 titleStack 已记录该标题
            if not content:
                continue

            # 构造完整 section（标题行 + 内容）
            full_section = f"{m.group(0)}\n{content}"

            # 输出 heading Node（标题本身）
            heading_node = self._make_heading_node(
                next(node_seq), heading_text, level, title_path=title_path
            )
            results.append(heading_node)

            # 处理 section 内容（可能包含 image / code_block / table / list / paragraph）
            section_nodes = self._process_section_content(
                full_section, title_path, node_seq, results, img_ctx,
            )

        return results

    # ============================== 标题区块内容处理 ==============================

    def _process_section_content(
        self,
        section_text: str,
        title_path: Optional[str],
        node_seq,
        results: List[dict],
        img_ctx: dict,
    ) -> None:
        """
        处理标题区块内的内容（可能包含多种 block 类型），结果直接 append 到 results。

        策略：
        1. 用 mistune AST 模式识别 block 边界（paragraph / code_block / table / list / image）
        2. 对于每个 block：
           - code_block / table → 原子块，整体输出一个 Node（不拆分）
           - image → 独立 image Node（仅本地图片落盘，远程跳过）
           - paragraph / list / block_quote → 检查是否超长，超长则内部拆句子为多小 Node + 共享 groupId；
             若段落内嵌 image，则遇图片先 flush 段前文本，产出 image Node，段后文本作为新 text Node
        """
        if mistune is None:
            # 降级为纯正则模式（简化处理）
            self._process_section_fallback(section_text, title_path, node_seq, results, img_ctx)
            return

        # 使用 mistune AST 模式解析
        md = mistune.create_markdown(renderer="ast")
        if _TABLE_PLUGIN:
            md.use(_TABLE_PLUGIN)

        try:
            tokens = md(section_text)
        except Exception as e:
            # mistune 解析异常属于非预期失败（输入已通过编码兜底读到），降级为正则模式可保证流程继续，
            # 但意味着丢失 mistune 的准确结构识别能力，需 error 级别便于排查
            logger.error("mistune 解析失败，降级为正则模式: %s", e, exc_info=True)
            self._process_section_fallback(section_text, title_path, node_seq, results, img_ctx)
            return

        # 遍历 token 树，提取 block
        for token in tokens:
            token_type = token.get("type", "")

            if token_type == "heading":
                # heading 已在 _parse_markdown 中单独处理，这里跳过
                continue

            elif token_type == "block_code":
                # 代码块：原子块，整体输出
                code_text = token.get("raw", "")
                if code_text.strip():
                    # 尝试提取语言信息
                    language = None
                    attrs = token.get("attrs", {})
                    if attrs and "info" in attrs:
                        language = attrs["info"]
                    results.append(
                        self._make_code_node(
                            next(node_seq),
                            code_text.strip(),
                            language=language,
                            title_path=title_path,
                            group_id=None,
                        )
                    )

            elif token_type == "table":
                # 表格：原子块，整体输出
                table_html = self._render_table_to_html(token)
                if table_html:
                    results.append(
                        self._make_table_node(
                            next(node_seq),
                            html=table_html,
                            title_path=title_path,
                            group_id=None,
                        )
                    )

            elif token_type == "image":
                # 顶层独立图片块：产出独立 image Node（仅本地图片落盘）
                self._emit_image_node(token, title_path, node_seq, results, img_ctx)

            elif token_type == "paragraph":
                # 段落：可能内嵌 image。先尝试拆出图片与文本片段
                self._emit_paragraph_with_images(
                    token, title_path, node_seq, results, img_ctx,
                )

            elif token_type == "list":
                # 列表：检查整体长度（列表内嵌图片少见，仍走纯文本提取）
                list_text = self._extract_text_from_token(token)
                if list_text.strip():
                    if len(list_text) <= self.threshold:
                        results.append(
                            self._make_text_node(
                                next(node_seq),
                                list_text.strip(),
                                title_path=title_path,
                                group_id=None,
                            )
                        )
                    else:
                        # 超长列表：内部拆句子为多个小 Node，共享同一 groupId（列表项整体不拆散；不再输出整块超长镜像父 Node）
                        group_id = next(node_seq)
                        sub_chunks = self._split_by_sentence_with_threshold(
                            list_text, self.threshold
                        )
                        for sub_text in sub_chunks:
                            if sub_text.strip():
                                results.append(
                                    self._make_text_node(
                                        next(node_seq),
                                        sub_text.strip(),
                                        title_path=title_path,
                                        group_id=group_id,
                                    )
                                )

            elif token_type == "block_quote":
                # 引用块：提取文本处理
                quote_text = self._extract_text_from_token(token)
                if quote_text.strip():
                    if len(quote_text) <= self.threshold:
                        results.append(
                            self._make_text_node(
                                next(node_seq),
                                quote_text.strip(),
                                title_path=title_path,
                                group_id=None,
                            )
                        )
                    else:
                        # 超长引用：内部拆句子为多个小 Node，共享同一 groupId
                        group_id = next(node_seq)
                        sub_chunks = self._split_by_sentence_with_threshold(
                            quote_text, self.threshold
                        )
                        for sub_text in sub_chunks:
                            if sub_text.strip():
                                results.append(
                                    self._make_text_node(
                                        next(node_seq),
                                        sub_text.strip(),
                                        title_path=title_path,
                                        group_id=group_id,
                                    )
                                )

            # 其他 block 类型（blank_line / thematic_break 等）忽略

        return

    # ============================== 无标题文档三级降级 ==============================

    def _process_no_title_document(self, text: str, node_seq, img_ctx: dict) -> List[dict]:
        """
        处理无标题文档：三级降级拆分（强段落 → 弱段落 → 句子）+ 阈值累加。
        （忠实复刻 Java processNoTitleDocument）

        段落内可能内嵌图片：每个强段落经 _emit_text_with_images 处理，
        遇图片先 flush 段前文本，产出 image Node，段后文本作为新 text Node。
        """
        results: List[dict] = []
        if not text:
            return results

        # 1. 按强段落分隔（多换行/双换行）拆分
        strong_paragraphs = re.split(STRONG_PARAGRAPH_SEP, text)

        buffer = ""
        for para in strong_paragraphs:
            trimmed_para = para.strip()
            if not trimmed_para:
                continue

            # 2. 检查段落是否显著超长（> threshold * 1.5）
            if len(trimmed_para) > self.threshold * 1.5:
                # 先输出当前 buffer
                if buffer:
                    self._emit_text_with_images(
                        buffer.strip(), None, node_seq, results, img_ctx,
                    )
                    buffer = ""

                # 超长段落：内部拆为多个小 Node，共享同一 groupId（不再输出整块超长镜像父 Node）
                # 注意：超长段落内的图片在此路径下不单独拆出（与原逻辑保持简单），仍按句子拆分文本
                group_id = next(node_seq)
                sub_chunks = self._split_oversized_paragraph(trimmed_para, self.threshold)
                for sub in sub_chunks:
                    if sub and sub.strip():
                        results.append(
                            self._make_text_node(
                                next(node_seq), sub.strip(), title_path=None, group_id=group_id
                            )
                        )
            else:
                # 3. 正常段落：累加到阈值（弹性区间，减少阈值附近对强关联语义的人为切断）
                # 图片预估字数参与判断：含图段落把"图片数 × IMAGE_ESTIMATED_CHARS"计入 add_len，
                # 使得 buffer 已较满时若再来含图段落能更准地提前 flush（避免长文本与图混在同一 emit 中
                # 被超长拆分为 groupId 子块、进而被 Java 装箱隔离规则跨 chunk 切开）。
                # 短文本+图场景因预估 120 字仍远低于弹性上界，不会误触发 flush，保持合并。
                image_count = len(IMAGE_PATTERN.findall(trimmed_para))
                add_len = (
                    len(trimmed_para)
                    + image_count * IMAGE_ESTIMATED_CHARS
                    + (2 if buffer else 0)
                )
                if should_flush_under_elastic(len(buffer), add_len, self.threshold):
                    # 超过弹性上界，先输出当前 buffer（含图片拆分）
                    self._emit_text_with_images(
                        buffer.strip(), None, node_seq, results, img_ctx,
                    )
                    buffer = trimmed_para
                else:
                    if buffer:
                        buffer += "\n\n"
                    buffer += trimmed_para

        # 输出剩余 buffer
        if buffer:
            self._emit_text_with_images(
                buffer.strip(), None, node_seq, results, img_ctx,
            )

        return results

    # ============================== 超长段落拆分 ==============================

    def _split_oversized_paragraph(self, para: str, threshold: int) -> List[str]:
        """
        拆分显著超长段落：先尝试按单换行拆分为弱段落，
        无换行或拆分后仍超长则按句子兜底拆分。
        （与 Java splitOversizedParagraph 一致）
        """
        # 1. 尝试按单换行拆分为弱段落（识别并保持列表项完整性）
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
        按单换行拆分弱段落，识别并保持列表项完整性。
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
        判断一行是否为 Markdown 列表项。
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
        子块之间以单换行连接，累加超过弹性上界则切出当前块（弹性区间减少人为切断强关联语义）。
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
            if should_flush_under_elastic(len(buffer), add_len, threshold):
                results.append(buffer.strip())
                buffer = trimmed
            else:
                if buffer:
                    buffer += "\n"
                buffer += trimmed

        if buffer:
            results.append(buffer.strip())

        return results

    # ============================== 句子拆分 ==============================

    def _split_by_sentence_with_threshold(self, text: str, threshold: int) -> List[str]:
        """
        按句子分隔符拆分文本，累加句子直到最接近阈值，不切断单个句子。
        句子是原子单位，不会被截断。累加采用弹性区间（减少对强关联句的人为切断）。
        """
        results: List[str] = []
        if not text or not text.strip():
            return results

        sentences = self._split_into_sentences(text)

        buffer = ""
        for sentence in sentences:
            if not sentence.strip():
                continue
            # 检查累加后是否超过弹性上界
            add_len = len(sentence)
            if should_flush_under_elastic(len(buffer), add_len, threshold):
                # 超过弹性上界，先输出当前 buffer
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

    # ============================== 图片处理 ==============================

    # 远程图片 URL 前缀（不下载，直接跳过）
    _REMOTE_URL_PREFIXES = ("http://", "https://", "ftp://", "ftps://")

    def _emit_paragraph_with_images(
        self,
        token: Dict[str, Any],
        title_path: Optional[str],
        node_seq,
        results: List[dict],
        img_ctx: dict,
    ) -> None:
        """处理含内嵌 image 的 paragraph token。

        遍历 children，按阅读顺序切分「文本片段」与「图片」：
        - 遇到 image token：先输出当前已累积的文本片段为 text Node，再产出 image Node；
        - 遇到 text/其他 token：累积到文本缓冲。
        段落无图片时退化为普通段落处理（超长拆句子 + groupId）。
        """
        children = token.get("children", [])
        if not children:
            return

        # 先收集图片，无图片则走原段落逻辑
        has_image = any(
            self._is_image_token(c) for c in children if isinstance(c, dict)
        )
        if not has_image:
            para_text = self._extract_text_from_token(token)
            if para_text.strip():
                self._emit_text_fragment(para_text.strip(), title_path, node_seq, results)
            return

        # 有图片：按顺序切分文本片段与图片
        text_buffer = ""
        for child in children:
            if not isinstance(child, dict):
                continue
            if self._is_image_token(child):
                # 先 flush 段前文本
                if text_buffer.strip():
                    self._emit_text_fragment(text_buffer.strip(), title_path, node_seq, results)
                    text_buffer = ""
                # 产出 image Node
                self._emit_image_node(child, title_path, node_seq, results, img_ctx)
            else:
                # 累积文本（递归取子文本，含 emphasis/strong 等 inline token）
                text_buffer += self._extract_text_from_token(child)

        # flush 段后剩余文本
        if text_buffer.strip():
            self._emit_text_fragment(text_buffer.strip(), title_path, node_seq, results)

    def _emit_text_with_images(
        self,
        text: str,
        title_path: Optional[str],
        node_seq,
        results: List[dict],
        img_ctx: dict,
    ) -> None:
        """处理一段原始文本（可能含 ![alt](url) 图片语法），按阅读顺序产出 text/image Node。

        用于无标题文档路径与 preamble：用正则识别图片，图片前的文本作为 text Node，
        图片产出 image Node，图片后的文本继续作为 text Node。
        """
        if not text or not text.strip():
            return

        last_end = 0
        has_image = False
        for m in IMAGE_PATTERN.finditer(text):
            has_image = True
            frag = text[last_end:m.start()]
            if frag.strip():
                self._emit_text_fragment(frag.strip(), title_path, node_seq, results)
            alt = m.group(1).strip()
            url = m.group(2).strip()
            self._emit_image_node_by_url(url, alt, title_path, node_seq, results, img_ctx)
            last_end = m.end()

        # 末尾剩余文本（或无图片时的整段文本）
        tail = text[last_end:]
        if tail.strip():
            self._emit_text_fragment(tail.strip(), title_path, node_seq, results)

        # 无图片且有文本时已被上面 tail 分支处理；has_image 仅用于可读性
        _ = has_image

    def _emit_text_fragment(
        self,
        text: str,
        title_path: Optional[str],
        node_seq,
        results: List[dict],
    ) -> None:
        """输出一段文本为 text Node（超长则内部拆句子 + 共享 groupId）。"""
        if not text or not text.strip():
            return
        text = text.strip()
        if len(text) <= self.threshold:
            results.append(
                self._make_text_node(
                    next(node_seq), text, title_path=title_path, group_id=None,
                )
            )
        else:
            group_id = next(node_seq)
            sub_chunks = self._split_by_sentence_with_threshold(text, self.threshold)
            for sub_text in sub_chunks:
                if sub_text.strip():
                    results.append(
                        self._make_text_node(
                            next(node_seq), sub_text.strip(),
                            title_path=title_path, group_id=group_id,
                        )
                    )

    @staticmethod
    def _is_image_token(token: Dict[str, Any]) -> bool:
        """判断 mistune token 是否为 image。"""
        return isinstance(token, dict) and token.get("type") == "image"

    def _emit_image_node(
        self,
        token: Dict[str, Any],
        title_path: Optional[str],
        node_seq,
        results: List[dict],
        img_ctx: dict,
    ) -> None:
        """从 mistune image token 提取 url/alt 并产出 image Node。"""
        attrs = token.get("attrs", {}) or {}
        url = attrs.get("url", "")
        # alt 文本可能来自 attrs.alt 或 children 内的 text
        alt = attrs.get("alt", "")
        if not alt:
            alt = self._extract_text_from_token(token).strip()
        self._emit_image_node_by_url(url, alt, title_path, node_seq, results, img_ctx)

    def _emit_image_node_by_url(
        self,
        url: str,
        alt: str,
        title_path: Optional[str],
        node_seq,
        results: List[dict],
        img_ctx: dict,
    ) -> None:
        """根据 url 产出 image Node（仅本地图片落盘，远程跳过）。

        url 可能带标题片段 "url \"title\""，需剥离。
        """
        if not url:
            return
        # 剥离 mistune/原生 markdown 可能的 title：url "title"
        url = url.strip().split()[0] if url.strip() else ""
        if not url:
            return

        # 远程链接：不下载，跳过（不产出 image Node，不阻断解析）
        if url.lower().startswith(self._REMOTE_URL_PREFIXES):
            logger.debug("Markdown 远程图片不处理，跳过: %s", url)
            return

        # 本地图片：相对 markdown 文件目录解析
        md_file_path: Path = img_ctx["file_path"]
        image_output_dir: Path = img_ctx["image_output_dir"]
        user_id = img_ctx["user_id"]
        document_id = img_ctx["document_id"]

        # 解析本地路径（支持相对/绝对，去除 URL query/fragment）
        clean_url = url.split("?")[0].split("#")[0]
        src_path = Path(clean_url)
        if not src_path.is_absolute():
            src_path = (md_file_path.parent / src_path).resolve()

        if not src_path.exists() or not src_path.is_file():
            logger.warning("Markdown 本地图片文件不存在，跳过: %s (resolved=%s)", url, src_path)
            return

        try:
            with open(src_path, "rb") as f:
                image_bytes = f.read()
        except Exception as e:
            logger.warning("Markdown 本地图片读取失败，跳过: %s, %s", src_path, e)
            return

        # 扩展名推断
        ext = src_path.suffix.lower().lstrip(".")
        if not ext:
            ext = "png"

        node_id = next(node_seq)
        img_filename = f"img_{node_id[1:]}.{ext}"
        img_path = image_output_dir / img_filename
        try:
            with open(img_path, "wb") as f:
                f.write(image_bytes)
        except Exception as e:
            logger.warning("Markdown 图片落盘失败，跳过: %s, %s", img_path, e)
            return

        relative_url = (
            f"{self.image_url_prefix}/{user_id}/{document_id}/{img_filename}"
        )
        img_hash = compute_hash(image_bytes)

        results.append({
            "id": node_id,
            "type": "image",
            "imagePath": relative_url,
            "caption": alt if alt else None,
            "titlePath": title_path,
            "page": 1,
            "bbox": None,
            "hash": img_hash,
        })
        logger.debug("Markdown 图片落盘: %s -> %s", src_path.name, img_filename)

    # ============================== mistune 辅助方法 ==============================

    def _extract_text_from_token(self, token: Dict[str, Any]) -> str:
        """
        从 mistune token 中提取纯文本（递归遍历 children）。
        image token 的 alt 不在此处提取（图片由 _emit_image_node 单独处理）。
        """
        if "raw" in token:
            return token["raw"]

        children = token.get("children", [])
        if not children:
            return ""

        text_parts = []
        for child in children:
            child_type = child.get("type", "")
            if child_type == "text":
                text_parts.append(child.get("raw", ""))
            elif "children" in child:
                text_parts.append(self._extract_text_from_token(child))

        return "".join(text_parts)

    def _render_table_to_html(self, token: Dict[str, Any]) -> Optional[str]:
        """
        将 mistune table token 渲染为 HTML 字符串。
        简化实现：直接使用 mistune 的 HTMLRenderer 渲染 table 部分。
        """
        if mistune is None:
            return None

        try:
            # 创建一个仅渲染 table 的 mini renderer
            renderer = mistune.HTMLRenderer()
            # 调用 renderer.table 方法
            children = token.get("children", [])
            if not children:
                return None

            # 手动拼接 table HTML（简化版）
            html_parts = ['<table border="1">']
            for child in children:
                child_type = child.get("type", "")
                if child_type == "table_head":
                    html_parts.append("<thead>")
                    for row in child.get("children", []):
                        if row.get("type") == "table_row":
                            html_parts.append("<tr>")
                            for cell in row.get("children", []):
                                if cell.get("type") == "table_cell":
                                    cell_text = self._extract_text_from_token(cell)
                                    align = cell.get("attrs", {}).get("align", None)
                                    align_attr = f' align="{align}"' if align else ""
                                    html_parts.append(f"<th{align_attr}>{cell_text}</th>")
                            html_parts.append("</tr>")
                    html_parts.append("</thead>")
                elif child_type == "table_body":
                    html_parts.append("<tbody>")
                    for row in child.get("children", []):
                        if row.get("type") == "table_row":
                            html_parts.append("<tr>")
                            for cell in row.get("children", []):
                                if cell.get("type") == "table_cell":
                                    cell_text = self._extract_text_from_token(cell)
                                    align = cell.get("attrs", {}).get("align", None)
                                    align_attr = f' align="{align}"' if align else ""
                                    html_parts.append(f"<td{align_attr}>{cell_text}</td>")
                            html_parts.append("</tr>")
                    html_parts.append("</tbody>")
            html_parts.append("</table>")
            return "".join(html_parts)
        except Exception as e:
            logger.warning("渲染 table token 失败: %s", e)
            return None

    # ============================== 降级模式（无 mistune） ==============================

    def _process_section_fallback(
        self,
        section_text: str,
        title_path: Optional[str],
        node_seq,
        results: List[dict],
        img_ctx: dict,
    ) -> None:
        """
        无 mistune 时的降级处理：纯正则模式，结果直接 append 到 results。

        简化策略：
        1. 识别图片 ![alt](url)，产出 image Node（仅本地图片落盘）
        2. 识别代码围栏 ```...```，整体输出为 code Node
        3. 识别表格（|...|），整体输出为 table Node（转 HTML）
        4. 剩余文本作为 paragraph，超长拆句子
        """
        # 1. 识别图片 ![alt](url)
        image_pattern = re.compile(r"!\[([^\]]*)\]\(([^)]+)\)")
        # 记录图片间/前后的文本片段，保持阅读顺序
        text_fragments: List[str] = []
        last_end = 0
        for m in image_pattern.finditer(section_text):
            frag = section_text[last_end:m.start()]
            if frag.strip():
                text_fragments.append(frag)
            alt = m.group(1).strip()
            url = m.group(2).strip()
            # 输出图片前的文本片段
            for frag in text_fragments:
                self._emit_text_fragment(frag.strip(), title_path, node_seq, results)
            text_fragments = []
            self._emit_image_node_by_url(url, alt, title_path, node_seq, results, img_ctx)
            last_end = m.end()
        # 末尾剩余文本
        tail = section_text[last_end:]
        if tail.strip():
            text_fragments.append(tail)
        for frag in text_fragments:
            self._emit_text_fragment(frag.strip(), title_path, node_seq, results)

        # 2. 识别代码围栏（在图片已剥离后的原 section_text 上识别，避免图片 url 误判）
        code_fence_pattern = re.compile(r"^```(\w*)\s*\n(.*?)\n```", re.DOTALL | re.MULTILINE)
        for m in code_fence_pattern.finditer(section_text):
            language = m.group(1) if m.group(1) else None
            code_text = m.group(2).strip()
            if code_text:
                results.append(
                    self._make_code_node(
                        next(node_seq),
                        code_text,
                        language=language,
                        title_path=title_path,
                        group_id=None,
                    )
                )

        # 3. 识别表格（连续 |...| 行）
        remaining_text = code_fence_pattern.sub("", section_text).strip()
        table_pattern = re.compile(r"(\|.*?\|\n)+", re.MULTILINE)
        for m in table_pattern.finditer(remaining_text):
            table_text = m.group(0).strip()
            if table_text:
                html = self._table_text_to_html(table_text)
                if html:
                    results.append(
                        self._make_table_node(
                            next(node_seq),
                            html=html,
                            title_path=title_path,
                            group_id=None,
                        )
                    )

        return

    def _table_text_to_html(self, table_text: str) -> Optional[str]:
        """
        将 Markdown 表格文本转换为 HTML（降级模式用）。
        """
        try:
            lines = [line.strip() for line in table_text.strip().split("\n") if line.strip()]
            if len(lines) < 2:
                return None

            # 过滤分隔行（|---|---|）
            data_lines = [line for line in lines if not re.match(r"^\|[\s\-:]+\|$", line)]
            if not data_lines:
                return None

            html_parts = ['<table border="1">']
            for i, line in enumerate(data_lines):
                cells = [cell.strip() for cell in line.split("|") if cell.strip()]
                tag = "th" if i == 0 else "td"
                row = "<tr>" + "".join(f"<{tag}>{cell}</{tag}>" for cell in cells) + "</tr>"
                html_parts.append(row)
            html_parts.append("</table>")
            return "".join(html_parts)
        except Exception as e:
            logger.warning("表格文本转 HTML 失败: %s", e)
            return None

    # ============================== titlePath 辅助 ==============================

    @staticmethod
    def _build_title_path(title_stack: List[Optional[str]]) -> Optional[str]:
        """
        从标题栈构建 titlePath 字符串（格式 "一级 > 二级 > 三级"）。
        """
        parts = [t for t in title_stack if t]
        if not parts:
            return None
        return " > ".join(parts)

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
        node_id: str,
        text: str,
        title_path: Optional[str],
        group_id: Optional[str],
    ) -> dict:
        """构造一个 text Node dict。group_id 非空表示同源整块拆出的子 Node（共享同 groupId）。"""
        return {
            "id": node_id,
            "type": "text",
            "text": text,
            "level": None,
            "language": None,
            "html": None,
            "titlePath": title_path,
            "groupId": group_id,
            "page": 1,
            "bbox": None,
        }

    @staticmethod
    def _make_heading_node(
        node_id: str,
        text: str,
        level: int,
        title_path: Optional[str],
    ) -> dict:
        """构造一个 heading Node dict"""
        return {
            "id": node_id,
            "type": "heading",
            "text": text,
            "level": level,
            "language": None,
            "html": None,
            "titlePath": title_path,
            "groupId": None,
            "page": 1,
            "bbox": None,
        }

    @staticmethod
    def _make_code_node(
        node_id: str,
        text: str,
        language: Optional[str],
        title_path: Optional[str],
        group_id: Optional[str],
    ) -> dict:
        """构造一个 code Node dict。group_id 非空表示同源整块拆出的子 Node。"""
        return {
            "id": node_id,
            "type": "code",
            "text": text,
            "level": None,
            "language": language,
            "html": None,
            "titlePath": title_path,
            "groupId": group_id,
            "page": 1,
            "bbox": None,
        }

    @staticmethod
    def _make_table_node(
        node_id: str,
        html: str,
        title_path: Optional[str],
        group_id: Optional[str],
    ) -> dict:
        """构造一个 table Node dict。group_id 非空表示同源整块拆出的子 Node。"""
        return {
            "id": node_id,
            "type": "table",
            "text": None,
            "level": None,
            "language": None,
            "html": html,
            "titlePath": title_path,
            "groupId": group_id,
            "page": 1,
            "bbox": None,
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
        logger.warning(
            "MarkdownParser 无法以常见编码解码 %s，回退 latin-1: %s",
            file_path, last_err,
        )
        with open(file_path, "r", encoding="latin-1") as f:
            return f.read()

    # ============================== 内容特征判定（supports 等价） ==============================

    @staticmethod
    def supports_extension(file_path: str) -> bool:
        """扩展名判定"""
        suffix = Path(file_path).suffix.lower().lstrip(".")
        return suffix in MARKDOWN_EXTENSIONS

    @staticmethod
    def supports_content(text: str) -> bool:
        """
        内容特征判定（与 Java MarkdownChunkStrategy.supports 一致）。
        供路由层 supports 二次判定使用。
        """
        if not text:
            return False
        if len(text) >= 200:
            sample = text[:200]
            return "# " in sample or "## " in sample or "```" in sample
        return "# " in text or "## " in text