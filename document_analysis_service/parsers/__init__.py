"""
parsers 子包：按文档类型组织的解析器集合。

- LineBasedParser: log / csv / tsv / txt 等行式文本（标准库 re，零新增依赖）
- CodeParser: 源代码文件（java/py/js/ts/...）（标准库 re，零新增依赖）
- MarkdownParser: Markdown 文件（mistune 3 结构识别 + 手写领域逻辑）
- HtmlParser: HTML 文件（beautifulsoup4 DOM 遍历 + 手写领域逻辑）
"""

from .linebased_parser import LineBasedParser, CHUNK_THRESHOLD
from .code_parser import CodeParser, CHUNK_THRESHOLD_CODE
from .markdown_parser import MarkdownParser, CHUNK_THRESHOLD as MD_CHUNK_THRESHOLD
from .html_parser import HtmlParser, CHUNK_THRESHOLD as HTML_CHUNK_THRESHOLD

__all__ = [
    "LineBasedParser",
    "CHUNK_THRESHOLD",
    "CodeParser",
    "CHUNK_THRESHOLD_CODE",
    "MarkdownParser",
    "MD_CHUNK_THRESHOLD",
    "HtmlParser",
    "HTML_CHUNK_THRESHOLD",
]
