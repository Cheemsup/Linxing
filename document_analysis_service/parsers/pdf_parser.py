"""
PDF 解析器

职责：将 PDF 文档解析为统一的 Node JSON 序列。
引擎：PyMuPDF (fitz) 抽取文本 / 图片，pdfplumber 抽取表格。
与 parsers 包内其它解析器（markdown / html / code / linebased / docx）平行。

Node JSON 协议（与 Java 侧 NodeDTO 对应）：
- id: str "n1"... 自管递增
- type: "heading" | "text" | "image" | "code" | "table"
- text / imagePath / html / language / level / page / bbox / hash / rowCount / colCount / titlePath / groupId
- groupId: 同一超长文本块内部拆出的小 Node 共享同一 groupId（标识同源整块），普通块 None

核心策略：
1. 跨页维护标题栈 [(level, title)]，每个 Node 都带 titlePath（标题块带其入栈后的路径，
   非标题块带其所属标题路径）——与 docx / markdown / html parser 一致
2. 标题识别：先做一遍全文档 span 字号扫描得到 body_median，标题阈值 = body_median * 1.3
   （粗体且字号不低于 body_median * 1.15 也算标题），level 按字号倍数分 1/2/3
3. 表格：pdfplumber.extract_tables() → HTML（原子块，首行 th）
4. 图片：按 xref 提取并保存到 storePath/chunk_images/{userId}/{docId}/，
   bbox 用 page.get_image_info(xref) 精确匹配（修复旧实现"全页图片共用首个 bbox"的 bug）
5. 代码：等宽字体名优先，否则按"多行 4 空格缩进"启发式
6. 页内按 bbox.y0 混合重排（2026-07-06 决策第五节）：每页收集图片/表格/文本块 Node，
   按 bbox[1]（y0）升序排序后输出，修复「图片/表格被堆到页首」——视觉元素与文本
   按真实阅读顺序交织
7. 跨页段落缝合（2026-07-06 决策第五节）：维护上一页最后一个 text Node，若本页首个
   text Node 字体特征（font_size + is_bold）一致则拼接为一段，修复「跨页段落断开」
8. 超长文本块（> _CHUNK_THRESHOLD=1000）：内部按句子拆为多个小 Node，共享同一 groupId（同源整块），
   不再整块超长 Node 返回 Java 侧；Java 侧据 groupId 合成父子 chunk。超长拆出的子块不参与跨页缝合
"""

import logging
import re
from pathlib import Path
from typing import List, Optional, Tuple

import fitz  # PyMuPDF
import pdfplumber
from PIL import Image

from ._common import (
    build_title_path,
    compute_hash,
    detect_code_language,
    guess_image_ext,
    make_node_seq,
    table_to_html,
)

logger = logging.getLogger("docling_analysis_service.parsers.pdf_parser")

# 字号扫描时每页最多采样的 span 数（避免超大文档 OOM/慢）
_SPAN_SAMPLE_PER_PAGE = 2000

# 标题字号倍数阈值（相对 body_median）
_HEADING_SIZE_RATIO = 1.3
_HEADING_BOLD_SIZE_RATIO = 1.15
# 标题 level 划分（相对 body_median）
_LEVEL1_RATIO = 1.8
_LEVEL2_RATIO = 1.5
_LEVEL3_RATIO = 1.3

# 等宽字体名特征
_MONO_FONT_HINTS = ("mono", "consol", "courier", "code", "menlo", "consolas", "jetbrains")

# bold 位（PyMuPDF flags 第 4 位，值 16）
_BOLD_FLAG = 16

# 超长文本块拆分阈值（与 markdown_parser / docx_parser / html_parser 的 CHUNK_THRESHOLD 一致），
# 超过的 fitz 文本 block 在内部按句子拆为多个小 Node，共享同一 groupId（同源整块），不再整块返回 Java 侧
_CHUNK_THRESHOLD = 1000

# 句子分隔符（中英文，与 markdown_parser / docx_parser 的 SENTENCE_DELIMITER 一致）
_SENTENCE_DELIMITER = re.compile(r"[。！？.!?；;]")


class PdfParser:
    """PDF 解析器，基于 PyMuPDF + pdfplumber。

    与其它 parsers 平行：parse(file_path, document_id, user_id) -> List[Node dict]。
    图片落盘到 self.image_store_dir/{user_id}/{document_id}/。
    """

    def __init__(
        self,
        image_store_dir: str,
        image_url_prefix: str = "/chunk_images",
    ):
        self.image_store_dir = Path(image_store_dir)
        self.image_url_prefix = image_url_prefix.rstrip("/")
        logger.info("PdfParser 初始化完成，图片存储目录: %s", self.image_store_dir)

    # ============================== 对外入口 ==============================

    def parse(
        self, file_path: str, document_id: int, user_id: int
    ) -> List[dict]:
        """
        解析 PDF，返回 Node JSON 列表。

        :param file_path: PDF 本地路径
        :param document_id: 文档 ID（图片目录隔离）
        :param user_id: 用户 ID（图片目录隔离）
        :return: Node dict 列表
        """
        file_path = Path(file_path)
        logger.info("PdfParser 开始解析: %s", file_path)

        image_output_dir = self.image_store_dir / str(user_id) / str(document_id)
        image_output_dir.mkdir(parents=True, exist_ok=True)

        nodes: List[dict] = []
        node_seq = make_node_seq()
        title_stack: List[Tuple[int, str]] = []

        # 跨页段落缝合状态：上一页最后一个 text Node 的引用
        last_text_node = None

        doc = fitz.open(str(file_path))
        try:
            total_pages = doc.page_count
            logger.info("PDF 共 %d 页", total_pages)

            # 预扫描：全文档 span 字号中位数，作为标题识别基线
            body_median = self._compute_body_font_size(doc)
            logger.info("PDF body 字号中位数: %.2f", body_median)

            with pdfplumber.open(str(file_path)) as pdf_plumber:
                for page_num in range(total_pages):
                    page = doc[page_num]
                    plumber_page = (
                        pdf_plumber.pages[page_num]
                        if page_num < len(pdf_plumber.pages)
                        else None
                    )

                    # 收集本页所有元素（图片 / 表格 / 文本块）
                    page_nodes = []
                    page_nodes.extend(
                        self._extract_images_from_page(
                            page, page_num + 1, image_output_dir,
                            user_id, document_id, node_seq, title_stack,
                        )
                    )
                    if plumber_page is not None:
                        page_nodes.extend(
                            self._extract_tables_from_page(
                                plumber_page, page_num + 1, node_seq, title_stack,
                            )
                        )
                    page_nodes.extend(
                        self._extract_text_blocks_from_page(
                            page, page_num + 1, node_seq, title_stack, body_median,
                        )
                    )

                    # 页内按 bbox.y0 升序混合重排（修复「图片/表格被堆到页首」）
                    # bbox 格式统一为 [x0, y0, width, height]，y0 = bbox[1]
                    page_nodes.sort(
                        key=lambda n: n["bbox"][1] if n.get("bbox") else float("inf")
                    )

                    # 跨页段落缝合：本页首个 text Node 与上一页末尾 text Node
                    # 字体特征一致则拼接（修复「跨页段落断开」）
                    # 仅当两侧都带 _font_size（即均为正常块、非超长拆出子块）才比对，
                    # 否则跳过缝合——超长拆出子块不带 _font_size，避免跨 group 误合并
                    if last_text_node is not None and page_nodes:
                        first_node = page_nodes[0]
                        first_fs = first_node.get("_font_size")
                        last_fs = last_text_node.get("_font_size")
                        if (
                            first_node.get("type") == "text"
                            and first_fs is not None
                            and last_fs is not None
                            and first_fs == last_fs
                            and first_node.get("_is_bold")
                            == last_text_node.get("_is_bold")
                        ):
                            last_text_node["text"] = (
                                last_text_node["text"] + "\n" + first_node["text"]
                            )
                            page_nodes = page_nodes[1:]

                    nodes.extend(page_nodes)

                    # 更新 last_text_node 为本页最后一个 text Node
                    # （若无则保持不变，支持跨多页段落缝合）
                    for n in reversed(page_nodes):
                        if n.get("type") == "text":
                            last_text_node = n
                            break
        finally:
            doc.close()

        # 清除临时字段（_font_size / _is_bold 仅用于跨页缝合判断）
        for n in nodes:
            n.pop("_font_size", None)
            n.pop("_is_bold", None)

        logger.info("PdfParser 解析完成: %s, 共 %d 个 Node", file_path, len(nodes))
        return nodes

    # ============================== body 字号基线 ==============================

    @staticmethod
    def _compute_body_font_size(doc: fitz.Document) -> float:
        """
        扫描全文 span 字号，返回中位数作为正文字号基线。
        用于标题识别（避免硬编码 14pt 在小字号/大字号 PDF 上失真）。
        扫描失败或无 span 时回退 12.0。
        """
        sizes: List[float] = []
        for page in doc:
            try:
                d = page.get_text("dict", sort=True)
            except Exception:
                continue
            count = 0
            for block in d.get("blocks", []):
                if block.get("type") != 0:
                    continue
                for line in block.get("lines", []):
                    for span in line.get("spans", []):
                        text = span.get("text", "")
                        if text.strip():
                            sizes.append(float(span.get("size", 12)))
                            count += 1
                            if count >= _SPAN_SAMPLE_PER_PAGE:
                                break
                    if count >= _SPAN_SAMPLE_PER_PAGE:
                        break
                if count >= _SPAN_SAMPLE_PER_PAGE:
                    break
        if not sizes:
            return 12.0
        sizes.sort()
        mid = len(sizes) // 2
        # 中位数（偶数取下中位，够用）
        return sizes[mid]

    # ============================== 图片提取 ==============================

    def _extract_images_from_page(
        self,
        page: fitz.Page,
        page_num: int,
        image_output_dir: Path,
        user_id: int,
        document_id: int,
        node_seq,
        title_stack: List[Tuple[int, str]],
    ) -> List[dict]:
        """从 PDF 页面提取图片，按 xref 精确匹配 bbox。"""
        nodes: List[dict] = []

        # xref -> bbox 列表（一张图可能被多次引用，取首个 bbox）
        xref_to_bbox = {}
        try:
            for info in page.get_image_info(xrefs=True):
                xref = info.get("xref")
                bbox = info.get("bbox")
                if xref is not None and bbox and xref not in xref_to_bbox:
                    r = fitz.Rect(bbox)
                    xref_to_bbox[xref] = [
                        r.x0, r.y0, r.width, r.height,
                    ]
        except Exception:
            # 极老 PyMuPDF 无 get_image_info，回退 None
            pass

        image_list = page.get_images(full=True)
        for img_info in image_list:
            try:
                xref = img_info[0]
                base_image = page.parent.extract_image(xref)
                image_bytes = base_image["image"]
                image_ext = base_image["ext"]

                node_id = next(node_seq)
                img_filename = f"img_{node_id[1:]}.{image_ext}"
                img_path = image_output_dir / img_filename
                with open(img_path, "wb") as f:
                    f.write(image_bytes)

                # 图片尺寸（PIL 失败则留空）
                width, height = None, None
                try:
                    with Image.open(img_path) as pil_img:
                        width, height = pil_img.size
                except Exception:
                    pass

                relative_url = (
                    f"{self.image_url_prefix}/{user_id}/{document_id}/{img_filename}"
                )
                bbox = xref_to_bbox.get(xref)
                img_hash = compute_hash(image_bytes)

                nodes.append({
                    "id": node_id,
                    "type": "image",
                    "imagePath": relative_url,
                    "titlePath": build_title_path(title_stack),
                    "page": page_num,
                    "bbox": bbox,
                    "width": width,
                    "height": height,
                    "hash": img_hash,
                })
                logger.debug("提取图片: %s (page=%d)", img_filename, page_num)
            except Exception as e:
                logger.warning("图片提取失败 (xref=%d): %s", img_info[0], e)

        return nodes

    # ============================== 表格提取 ==============================

    def _extract_tables_from_page(
        self,
        page: pdfplumber.page.Page,
        page_num: int,
        node_seq,
        title_stack: List[Tuple[int, str]],
    ) -> List[dict]:
        """使用 pdfplumber 提取表格为 HTML 原子块。"""
        nodes: List[dict] = []
        try:
            tables = page.extract_tables()
            # find_tables 返回 Table 对象列表，与 extract_tables 顺序一致
            table_objs = page.find_tables()
            for table_index, table_data in enumerate(tables):
                if not table_data:
                    continue

                html = table_to_html(table_data)
                row_count = len(table_data)
                col_count = max(len(row) for row in table_data) if table_data else 0

                bbox = None
                try:
                    if table_index < len(table_objs):
                        tb = table_objs[table_index].bbox
                        bbox = [tb[0], tb[1], tb[2] - tb[0], tb[3] - tb[1]]
                except Exception:
                    pass

                nodes.append({
                    "id": next(node_seq),
                    "type": "table",
                    "html": html,
                    "rowCount": row_count,
                    "colCount": col_count,
                    "titlePath": build_title_path(title_stack),
                    "page": page_num,
                    "bbox": bbox,
                })
                logger.debug(
                    "提取表格: %d行%d列 (page=%d)", row_count, col_count, page_num
                )
        except Exception as e:
            logger.warning("表格提取失败 (page=%d): %s", page_num, e)

        return nodes

    # ============================== 文本块提取 ==============================

    def _extract_text_blocks_from_page(
        self,
        page: fitz.Page,
        page_num: int,
        node_seq,
        title_stack: List[Tuple[int, str]],
        body_median: float,
    ) -> List[dict]:
        """
        提取文本块，基于字号 / 粗体识别标题，基于字体名 / 缩进识别代码。
        标题会更新 title_stack，从而让后续块的 titlePath 反映层级。
        """
        nodes: List[dict] = []
        blocks = page.get_text("dict", sort=True)["blocks"]

        for block in blocks:
            if block.get("type") != 0:  # 跳过图片块
                continue

            lines = block.get("lines", [])
            if not lines:
                continue

            # 合并 span 文本：行内直接拼接，行间补换行
            # （旧实现把所有 span 拼成一串，导致多行文本被挤成一行，丢失段落结构）
            line_texts = []
            for line in lines:
                lt = "".join(s.get("text", "") for s in line.get("spans", []))
                if lt:
                    line_texts.append(lt)
            text = "\n".join(line_texts)
            if not text.strip():
                continue

            # 取该 block 出现频次最高的字号 / 是否粗体，作为块级特征
            spans = [s for line in lines for s in line.get("spans", [])]
            font_size, is_bold, is_mono = self._summarize_block_font(spans, body_median)

            is_heading, heading_level = self._detect_heading(
                font_size, is_bold, body_median
            )
            # heading 优先级高于 code（粗体大字号的代码标题不误判为 code）
            is_code = False
            if not is_heading:
                is_code = self._detect_code(spans, is_mono, text)

            bbox = self._bbox_from_block(block)

            if is_heading:
                heading_text = text.strip()
                level = heading_level
                # 更新标题栈：弹出 level >= 当前的，压入当前
                while title_stack and title_stack[-1][0] >= level:
                    title_stack.pop()
                title_stack.append((level, heading_text))

                nodes.append({
                    "id": next(node_seq),
                    "type": "heading",
                    "text": heading_text,
                    "level": level,
                    "titlePath": build_title_path(title_stack),
                    "page": page_num,
                    "bbox": bbox,
                })
            elif is_code:
                nodes.append({
                    "id": next(node_seq),
                    "type": "code",
                    "text": text.strip(),
                    "language": detect_code_language(text),
                    "titlePath": build_title_path(title_stack),
                    "page": page_num,
                    "bbox": bbox,
                })
            else:
                stripped = text.strip()
                title_path = build_title_path(title_stack)
                if len(stripped) <= _CHUNK_THRESHOLD:
                    nodes.append({
                        "id": next(node_seq),
                        "type": "text",
                        "text": stripped,
                        "groupId": None,
                        "titlePath": title_path,
                        "page": page_num,
                        "bbox": bbox,
                        # 临时字段，仅用于跨页段落缝合判断，parse() 结束前清除
                        "_font_size": font_size,
                        "_is_bold": is_bold,
                    })
                else:
                    # 超长文本块：内部按句子拆为多个小 Node，共享同一 groupId（同源整块），不再整块返回 Java 侧。
                    # 超长拆出的子块不参与跨页段落缝合（不带 _font_size/_is_bold），以免与相邻块错误合并、
                    # 破坏 groupId 隔离；超长内容在组内自身连续完整，缝合在此属可选优化，非正确性要求。
                    group_id = next(node_seq)
                    sub_chunks = self._split_by_sentence_with_threshold(
                        stripped, _CHUNK_THRESHOLD
                    )
                    for sub_text in sub_chunks:
                        sub_text = sub_text.strip()
                        if not sub_text:
                            continue
                        nodes.append({
                            "id": next(node_seq),
                            "type": "text",
                            "text": sub_text,
                            "groupId": group_id,
                            "titlePath": title_path,
                            "page": page_num,
                            "bbox": bbox,
                        })

        return nodes

    # ============================== 字体特征汇总 ==============================

    @staticmethod
    def _summarize_block_font(spans: List[dict], body_median: float) -> Tuple[float, bool, bool]:
        """
        汇总 block 内 span 的字号 / 粗体 / 等宽特征。
        字号取众数（最常见档位），避免一个块里混入小注脚字号拉低判断。
        """
        if not spans:
            return body_median, False, False

        # 字号众数
        size_counter: dict = {}
        bold_count = 0
        mono_count = 0
        for s in spans:
            size = round(float(s.get("size", body_median)), 1)
            size_counter[size] = size_counter.get(size, 0) + len(s.get("text", ""))
            if int(s.get("flags", 0)) & _BOLD_FLAG:
                bold_count += len(s.get("text", ""))
            font_name = (s.get("font", "") or "").lower()
            if any(m in font_name for m in _MONO_FONT_HINTS):
                mono_count += len(s.get("text", ""))

        total_chars = sum(size_counter.values()) or 1
        # 众数字号（按字符数加权）
        font_size = max(size_counter.items(), key=lambda kv: kv[1])[0]
        is_bold = bold_count * 2 >= total_chars  # 过半字符粗体视为粗体块
        is_mono = mono_count * 2 >= total_chars
        return font_size, is_bold, is_mono

    # ============================== 标题 / 代码启发式 ==============================

    @staticmethod
    def _detect_heading(
        font_size: float, is_bold: bool, body_median: float
    ) -> Tuple[bool, int]:
        """
        标题启发式：字号显著大于正文，或（粗体且字号不低于正文 1.15 倍）。
        level 按字号倍数分 1/2/3。
        """
        if body_median <= 0:
            return False, 1
        ratio = font_size / body_median
        if ratio >= _LEVEL1_RATIO:
            return True, 1
        if ratio >= _LEVEL2_RATIO:
            return True, 2
        if ratio >= _LEVEL3_RATIO:
            return True, 3
        # 粗体且略大于正文：作为 level 3 标题（避免把正文粗体强调误判，要求 ratio>=1.15）
        if is_bold and ratio >= _HEADING_BOLD_SIZE_RATIO:
            return True, 3
        return False, 1

    @staticmethod
    def _detect_code(spans: List[dict], is_mono: bool, text: str) -> bool:
        """
        代码启发式：等宽字体优先；否则按"多行 4 空格缩进"判断。
        相比旧实现 text.count('    ') >= 3，改用"以 4 空格开头的行数 >= 2"，
        降低对正文段落里偶然多空格的误判。
        """
        if is_mono:
            return True
        # 4 空格缩进行判定：行首 4+ 空格的行占比过半视为代码块
        indent_lines = 0
        total_lines = 0
        for line in text.split("\n"):
            total_lines += 1
            if re.match(r"^ {4,}\S", line):
                indent_lines += 1
        return indent_lines >= 2 and indent_lines * 2 >= max(total_lines, 1)

    # ============================== bbox 辅助 ==============================

    @staticmethod
    def _bbox_from_block(block: dict) -> List[float]:
        """从 fitz block bbox 转为 [x, y, width, height]。"""
        b = block["bbox"]
        return [b[0], b[1], b[2] - b[0], b[3] - b[1]]

    # ============================== 扩展名 / 内容特征判定 ==============================

    @staticmethod
    def supports_extension(file_path: str) -> bool:
        """扩展名判定"""
        return Path(file_path).suffix.lower() == ".pdf"

    # ============================== 超长文本拆分 ==============================

    def _split_by_sentence_with_threshold(self, text: str, threshold: int) -> List[str]:
        """按句子分隔符拆分文本，累加句子直到最接近阈值，不切断单个句子。

        （与 docx_parser._split_by_sentence_with_threshold / markdown_parser / html_parser 一致）
        用于超长 fitz 文本块的内部二次切分：拆出的小 Node 由调用方写同一 groupId。
        """
        results: List[str] = []
        if not text or not text.strip():
            return results

        sentences = self._split_into_sentences(text)

        buffer = ""
        for sentence in sentences:
            if not sentence.strip():
                continue
            add_len = len(sentence)
            if buffer and len(buffer) + add_len > threshold:
                results.append(buffer.strip())
                buffer = sentence
            else:
                buffer += sentence

        if buffer:
            results.append(buffer.strip())

        return results

    @staticmethod
    def _split_into_sentences(text: str) -> List[str]:
        """按句子分隔符拆分文本，保留分隔符在句子末尾。

        （与 docx_parser._split_into_sentences / markdown_parser / html_parser 一致）
        """
        sentences: List[str] = []
        if not text:
            return sentences

        last_end = 0
        for m in _SENTENCE_DELIMITER.finditer(text):
            delimiter_end = m.end()
            sentence = text[last_end:delimiter_end]
            if sentence.strip():
                sentences.append(sentence)
            last_end = delimiter_end

        if last_end < len(text):
            remaining = text[last_end:]
            if remaining.strip():
                sentences.append(remaining)

        return sentences
