"""
文档类型路由器

职责：根据文件扩展名 + 内容特征二次判定文档类型，分发给对应的解析器。
替代旧 DocumentParser._detect_document_type 的 if-else，对应旧 Java ChunkStrategy.supports() 逻辑。

路由优先级（与旧 Java ChunkStrategyFactory 一致）：
    markdown > html > code > pdf/docx > linebased

返回 {"documentType": str, "nodes": List[dict]}，与旧 DocumentParser.parse 输出一致。
"""

import logging
import os
from pathlib import Path
from typing import List, Dict, Any

from .linebased_parser import LineBasedParser
from .code_parser import CodeParser
from .markdown_parser import MarkdownParser

logger = logging.getLogger("docling_analysis_service.parsers.router")

# 各类型解析器单例（图片解析器 pdf/docx 仍由 DocumentParser 处理，路由器只负责 md/html/code/linebased）
_markdown_parser = None
_html_parser = None
_code_parser = None
_linebased_parser = None


def _get_markdown_parser():
    global _markdown_parser
    if _markdown_parser is None:
        _markdown_parser = MarkdownParser()
    return _markdown_parser


def _get_html_parser():
    global _html_parser
    if _html_parser is None:
        # 延迟导入，bs4 仅在解析 HTML 时才需要
        from .html_parser import HtmlParser
        _html_parser = HtmlParser()
    return _html_parser


def _get_code_parser():
    global _code_parser
    if _code_parser is None:
        _code_parser = CodeParser()
    return _code_parser


def _get_linebased_parser():
    global _linebased_parser
    if _linebased_parser is None:
        _linebased_parser = LineBasedParser()
    return _linebased_parser


# 扩展名 → 文档类型映射（与旧 Java ChunkStrategy.supports() 的扩展名判定对齐）
_MARKDOWN_EXTS = {"md", "markdown"}
_HTML_EXTS = {"html", "htm"}
_CODE_EXTS = {
    "java", "py", "js", "ts", "go", "rs", "c", "cpp", "cs", "kt",
    "rb", "php", "swift", "scala", "hs", "lua", "r", "sh", "bash", "sql",
}
_STRUCTURED_EXTS = {"pdf", "docx", "doc", "xlsx"}  # 仍由 DocumentParser 解析
_LINEBASED_EXTS = {"log", "csv", "tsv", "txt"}

# 代码内容特征指示符（与旧 Java CodeChunkStrategy.CODE_INDICATOR 一致）
_CODE_INDICATOR_KEYWORDS = (
    "package", "import", "class", "public class", "private class",
    "def ", "function ", "fn ", "func ", "int main", "void main",
)


def detect_document_type(file_path) -> str:
    """
    按扩展名 + 内容特征二次判定文档类型。

    判定顺序：
    1. 扩展名直接命中结构化类型（pdf/docx/xlsx）→ 返回对应类型（交由 DocumentParser）
    2. 扩展名命中 markdown/html/code/linebased → 返回对应类型
    3. 扩展名未命中 → 读内容特征：
       - 含 `# `/`## `/` ``` ` → markdown
       - 含 `<html`/`<body`/`<div` → html
       - 含 package/class/def/function 等代码指示符 → code
       - 行长度方差小 / 频繁空行块 → linebased
       - 否则 fallback 到 linebased（最通用的文本兜底）

    :param file_path: 文件路径（str 或 Path）
    :return: 文档类型字符串（pdf/docx/xlsx/markdown/html/code/linebased）
    """
    suffix = Path(file_path).suffix.lower().lstrip(".")
    file_path = Path(file_path)

    # 1. 结构化文档（仍由 DocumentParser 处理）
    if suffix == "pdf":
        return "pdf"
    if suffix in ("docx", "doc"):
        return "docx"
    if suffix == "xlsx":
        return "xlsx"

    # 2. 扩展名直接命中
    if suffix in _MARKDOWN_EXTS:
        return "markdown"
    if suffix in _HTML_EXTS:
        return "html"
    if suffix in _CODE_EXTS:
        return "code"
    if suffix in _LINEBASED_EXTS:
        return "linebased"

    # 3. 内容特征二次判定
    sample = _read_sample(file_path)
    if sample:
        if _looks_like_markdown(sample):
            return "markdown"
        if _looks_like_html(sample):
            return "html"
        if _looks_like_code(sample):
            return "code"

    # linebased 作为最通用的文本兜底（与旧 Java RecursiveChunkStrategy 兜底语义对应）
    return "linebased"


def parse(file_path, document_id: int, user_id: int) -> Dict[str, Any]:
    """
    路由分发：按检测到的文档类型调用对应解析器，返回统一 Node JSON。

    :param file_path: 文件路径
    :param document_id: 文档 ID（图片目录隔离，md/html/code/linebased 无图片，仅透传）
    :param user_id: 用户 ID
    :return: {"documentType": str, "nodes": List[dict]}
    """
    file_path = Path(file_path)
    doc_type = detect_document_type(file_path)
    logger.info("路由判定文档类型: %s (file=%s)", doc_type, file_path.name)

    try:
        if doc_type == "markdown":
            nodes = _get_markdown_parser().parse(file_path, document_id, user_id)
        elif doc_type == "html":
            nodes = _get_html_parser().parse(file_path, document_id, user_id)
        elif doc_type == "code":
            nodes = _get_code_parser().parse(file_path, document_id, user_id)
        elif doc_type == "linebased":
            nodes = _get_linebased_parser().parse(file_path, document_id, user_id)
        else:
            # pdf/docx/xlsx 不应由路由器处理（DocumentParser 负责）；防御性返回空
            logger.warning("路由器收到结构化文档类型 %s，应交由 DocumentParser 处理", doc_type)
            nodes = []
    except Exception as e:
        logger.exception("解析器 %s 处理失败: %s", doc_type, file_path.name)
        raise

    logger.info("解析完成: file=%s, type=%s, nodes=%d", file_path.name, doc_type, len(nodes))
    return {"documentType": doc_type, "nodes": nodes}


# ------------------------------ 内容特征判定 ------------------------------

def _read_sample(file_path: Path, max_chars: int = 500) -> str:
    """读取文件前若干字符用于内容特征判定（多编码兜底）。"""
    for encoding in ("utf-8", "gbk", "latin-1"):
        try:
            with open(file_path, "r", encoding=encoding) as f:
                return f.read(max_chars)
        except (UnicodeDecodeError, OSError):
            continue
    return ""


def _looks_like_markdown(sample: str) -> bool:
    """Markdown 内容特征：含 `# `/`## `/` ``` `（与旧 Java MarkdownChunkStrategy.supports 一致）。"""
    if "# " in sample or "## " in sample or "```" in sample:
        return True
    return sample.lstrip().startswith("#")


def _looks_like_html(sample: str) -> bool:
    """HTML 内容特征：含典型 HTML 标签。"""
    lower = sample.lower()
    return ("<html" in lower or "<body" in lower or "<div" in lower
            or "<head" in lower or "<!doctype html" in lower)


def _looks_like_code(sample: str) -> bool:
    """代码内容特征：含 package/import/class/def/function 等指示符（与旧 Java CODE_INDICATOR 一致）。"""
    return any(kw in sample for kw in _CODE_INDICATOR_KEYWORDS)
