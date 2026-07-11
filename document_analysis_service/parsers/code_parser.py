"""
源代码文件解析器（java / py / js / ts / go / rs / c / cpp / cs / kt / rb / php / swift / scala / hs / lua / r / sh / bash / sql）

职责：将源代码文件解析为统一的 Node JSON 序列。

选型决策：不引入 tree-sitter（优势在「超长方法按逻辑块切」），只做旧方案java侧正则的迁移并健全化。

核心策略 —— 类/函数边界拆分：
1. CLASS_PATTERN / FUNCTION_PATTERN 收集所有 class/func 匹配位置，按 start 排序
2. 首匹配前的 preamble（import / package / 注释 / 全局变量）作为独立块（titlePath=None）
3. 遍历匹配：
   - class → 更新 currentClassName，titlePath = 类名
   - function → titlePath = currentClassName + " > " + funcName（无 class 则单独 funcName）
4. 每个块文本 = 从当前 match.start 到下一个 match.start（trim 后空则跳过）
5. 无任何 class/func 匹配 → 整个文件作为一个 code Node（titlePath=None）

Node JSON 协议（与 Java 侧 NodeDTO 对应）：
- id: str "n1"... 自管递增
- type: "code"
- text: 代码块文本
- language: 检测出的语言（java/py/js/ts...），None 也行
- titlePath: str（class 名或 "class > func"），无则 None
- groupId: str 或 None（超长块内部拆出的小 Node 共享同一 groupId 标识同源整块；普通块 None）
- page: 1
- bbox: None
- html: None
"""

import logging
import re
from pathlib import Path
from typing import List, Optional, Tuple

logger = logging.getLogger("docling_analysis_service.parsers.code_parser")

CHUNK_THRESHOLD_CODE = 1500

CODE_EXTENSIONS = {
    "java", "py", "js", "ts", "go", "rs", "c", "cpp", "cs", "kt",
    "rb", "php", "swift", "scala", "hs", "lua", "r", "sh", "bash", "sql",
}

CODE_INDICATOR = re.compile(
    r"\b(package|import|class|public class|private class|def |function |fn |func |int main|void main)\b"
)

# 旧 Java CLASS_PATTERN（迁移并健全化）：
# 旧版缺陷：
# 1) 修饰符组只允许 0~1 个（(?:public|...|data)?\s*），"public final class X" /
#    "public abstract class X" / "public static class X" 等多修饰符声明漏判
# 2) 缺 export / override（TS export class、Kotlin override class）
# 3) "enum class Foo" / "sealed class Foo" 这类"关键字 class 名字"被误判：
#    旧版正则在 "enum class Foo" 上把 group(3) 命中 "enum"、group(4) 命中 "class"，name 取错
# 健全化：修饰符组改为 (mod\s+)* 多修饰符；并在主关键字后允许可选的 "class"/"struct"
# （Kotlin/Scala 的 "enum class" / "data class" / "sealed class"），name 正确取到 Foo。
# group(1)=前导空白 group(2)=修饰符组 group(3)=class/interface/enum/... group(4)=类名
CLASS_PATTERN = re.compile(
    r"^(\s*)"
    r"((?:(?:public|private|protected|abstract|final|sealed|open|data|static|inline|override|export)\s+)*)"
    r"(class|interface|enum|object|trait|struct|impl|module)(?:\s+(?:class|struct))?\s+(\w+)",
    re.MULTILINE,
)

# 旧 Java FUNCTION_PATTERN（迁移并健全化）：
# 原始 Java 版本：
#   ^(\s*)((?:public|private|protected|static|final|abstract|synchronized|async|def|fn|func|function)\s+)+
#   \S+\s+\w+\s*\([^)]*\)\s*\{?
# 原版问题：
# 1) 修饰符组用 (mod\s+)+，要求"至少一个修饰符"，导致无修饰符的 JS `function foo(){}` /
#    Python `def foo():` / Go `func foo(){}` / Rust `fn foo(){}` / Kotlin `fun foo(){}`
#    ——各语言最常见的函数声明写法——全部漏判
# 2) 关键字 def/fn/func/fun/function 被塞进"修饰符组"而非"关键字前缀"，
#    其后又要求 "\S+\s+\w+\s*\("（返回类型 + 名字），无返回类型的签名漏判
# 3) Kotlin `fun` 关键字缺失；Rust `pub fn`、Go `func main(){` 缺失
# 4) C 风格 `int main(){` / `void main(){`（无修饰符前缀）漏判
# 5) `if(...)` / `while(...)` / `for(...)` / `switch(...)` / `catch(...)` /
#    `synchronized(...)` 这类控制流语句会被误判为函数声明
# 健全化：分两个分支
#   A：前缀引导 —— 至少一个修饰符（含 pub/override/export/async/open 等），
#      或 def/fn/func/fun/function/sub/proc 关键字前缀；
#      后接可选返回类型 + name + (params) + 可选后缀（-> | => | : type）+ 可选 {
#      覆盖现代语言（Java/C#/TS/JS/Kotlin/Python/Go/Rust/Swift）的函数声明
#   B：C 风格顶级声明 —— TYPE NAME(...){，必须有 {（避免把函数调用 `foo(x);` 误判）
#      覆盖 C/C++ 的 `int main(){`、`void helper(int x){` 等
# 命名捕获 nameA / nameB 即函数名；_extract_func_name 也作用于整段匹配串兜底取名
FUNCTION_PATTERN = re.compile(
    r"^(\s*)"
    r"(?:"
    # 分支 A：前缀引导（修饰符 或 关键字）
    r"(?P<prefix>(?:(?:public|private|protected|static|final|abstract|sealed|synchronized|open|override|async|export|default|native|inline|pub)\s+)+"
    r"|(?:def|fn|func|fun|function|sub|proc)\s+)"
    r"(?:[\w<>:\[\],\s]*?\s+)?(?P<nameA>\w+)\s*\([^)]*\)\s*(?:(?:->|=>|:[^\n{]*)?\s*)?\{?"
    r"|"
    # 分支 B：C 风格顶级声明 TYPE NAME(...) {
    r"(?P<ctype>[\w:<>\[\],\s]+?)\s+(?P<nameB>\w+)\s*\([^)]*\)\s*\{"
    r")",
    re.MULTILINE,
)

# 旧 Java extractFuncName 内部正则：\s+(\w+)\s*\(
# 修饰符引导型签名下，匹配最后一个 "标识符 (" 即函数名（search 取首个命中，
# 对 `public void foo(` 命中 "foo"；对 `private static int bar(` 也命中 "bar"）
_FUNC_NAME_PATTERN = re.compile(r"\s+(\w+)\s*\(")

# 关键字引导型签名的函数名提取：def/fn/func/function/fun name( → 取 name
# 配合 _extract_func_name 优先使用，避免把关键字本身误当函数名
_KEYWORD_FUNC_NAME_PATTERN = re.compile(
    r"\b(?:def|fn|func|function|fun)\s+(\w+)\s*\("
)

# 扩展名 → 语言映射（优先于 CODE_INDICATOR 启发）
_EXT_LANGUAGE_MAP = {
    "java": "java",
    "py": "python",
    "js": "javascript",
    "ts": "typescript",
    "go": "go",
    "rs": "rust",
    "c": "c",
    "cpp": "cpp",
    "cs": "csharp",
    "kt": "kotlin",
    "rb": "ruby",
    "php": "php",
    "swift": "swift",
    "scala": "scala",
    "hs": "haskell",
    "lua": "lua",
    "r": "r",
    "sh": "shell",
    "bash": "bash",
    "sql": "sql",
}

# 超长块子 Node 拆分：句子分隔符（中英文，与 linebased_parser 一致）
_SENTENCE_DELIMITER = re.compile(r"[。！？.!?；;]")

# 超长块子 Node 拆分：空行分隔（强逻辑边界）
_BLANK_LINE_SEP = re.compile(r"\n\s*\n")

# 超长块子 Node 拆分兜底行数（无空行无句子分隔时，每 N 行切一刀）
_FALLBACK_LINES_PER_SUB = 40


class CodeParser:
    """源代码文件解析器，基于标准库 re 实现

    忠实迁移旧 Java CodeChunkStrategy 的正则与 splitByClassOrFunction 逻辑，
    超长块拆分改用「内部拆为多个小 Node + 共享 groupId」方案（不再输出整块超长镜像父 Node）。
    """

    def __init__(self, chunk_threshold: int = CHUNK_THRESHOLD_CODE):
        """
        :param chunk_threshold: 超长块判定阈值（默认 1500，与旧 Java 一致）
        """
        self.threshold = chunk_threshold
        logger.info("CodeParser 初始化完成，阈值: %d", self.threshold)

    # ============================== 对外入口 ==============================

    def parse(
        self, file_path: str, document_id: int, user_id: int
    ) -> List[dict]:
        """
        解析源代码文件，返回 Node JSON 列表

        :param file_path: 文件本地路径
        :param document_id: 文档 ID（保留参数，与 parser.py 风格一致）
        :param user_id: 用户 ID（保留参数，与 parser.py 风格一致）
        :return: Node dict 列表，每个 Node 形如
                 {id, type, text, language, titlePath, groupId, page, bbox, html}
        """
        file_path = Path(file_path)
        logger.info("CodeParser 开始解析: %s", file_path)

        try:
            text = self._read_text(file_path)
        except Exception as e:
            logger.warning("CodeParser 读取文件失败: %s, %s", file_path, e)
            return []

        if not text or not text.strip():
            logger.info("CodeParser 文件内容为空: %s", file_path)
            return []

        # 语言推断：优先扩展名，否则 CODE_INDICATOR 启发
        language = self._detect_language(file_path, text)

        # 类/函数边界拆分（含 groupId 标注）
        nodes = self._split_by_class_or_function(text, language)

        logger.info(
            "CodeParser 解析完成: %s, 共 %d 个 Node", file_path, len(nodes)
        )
        return nodes

    # ============================== 核心：类/函数边界拆分 ==============================

    def _split_by_class_or_function(
        self, text: str, language: Optional[str]
    ) -> List[dict]:
        """
        按类/函数边界拆分源代码，生成 Node JSON 列表。
        （忠实迁移 Java CodeChunkStrategy.splitByClassOrFunction）

        与 Java 的区别：
        - 输出 Node dict（而非 ChunkResult）
        - 超长块不再走 RecursiveTextSplitter.refine，
          改为输出多个小 Node（内部语义/逻辑行切分），共享同一 groupId
        """
        results: List[dict] = []
        if not text:
            return results

        node_seq = self._make_node_seq()

        # 1. 收集所有 class/func 匹配位置
        matches: List[Tuple[int, str, str]] = []  # (start, type, name)

        for m in CLASS_PATTERN.finditer(text):
            # group(4) = 类名，与 Java classMatcher.group(4) 一致
            matches.append((m.start(), "class", m.group(4)))

        for m in FUNCTION_PATTERN.finditer(text):
            # extractFuncName 作用于整个匹配串
            func_name = self._extract_func_name(m.group(0))
            matches.append((m.start(), "function", func_name))

        # 2. 无任何匹配 → 整个文件作为一个 code Node（titlePath=None）
        if not matches:
            trimmed = text.strip()
            if trimmed:
                # 整个文件可能超长，但无结构信息可拆，作为单个 Node 输出
                results.append(
                    self._make_code_node(
                        next(node_seq), trimmed, language=language,
                        title_path=None, group_id=None,
                    )
                )
            return results

        # 3. 按 start 排序
        matches.sort(key=lambda x: x[0])

        # 4. 首匹配前的 preamble 作为独立块（import / package / 注释 / 全局变量）
        first_start = matches[0][0]
        if first_start > 0:
            preamble = text[:first_start].strip()
            if preamble:
                results.append(
                    self._make_code_node(
                        next(node_seq), preamble, language=language,
                        title_path=None, group_id=None,
                    )
                )

        # 5. 遍历匹配，构造块
        current_class_name: Optional[str] = None
        for i, (start, m_type, m_name) in enumerate(matches):
            # 构造 titlePath
            if m_type == "class":
                current_class_name = m_name
                block_title_path = m_name
            elif current_class_name is not None:
                block_title_path = f"{current_class_name} > {m_name}"
            else:
                block_title_path = m_name

            # 块文本 = 从当前 match.start 到下一个 match.start
            end = matches[i + 1][0] if i + 1 < len(matches) else len(text)
            block_text = text[start:end].strip()

            if not block_text:
                continue

            # 6. 普通块 / 超长块分流
            if len(block_text) <= self.threshold:
                results.append(
                    self._make_code_node(
                        next(node_seq), block_text, language=language,
                        title_path=block_title_path, group_id=None,
                    )
                )
            else:
                # 超长块：内部拆为多个小 Node，共享同一 groupId（不再输出整块超长镜像父 Node）
                group_id = next(node_seq)
                sub_chunks = self._split_oversized_block(block_text, self.threshold)
                for sub_text in sub_chunks:
                    if sub_text and sub_text.strip():
                        results.append(
                            self._make_code_node(
                                next(node_seq), sub_text.strip(), language=language,
                                title_path=block_title_path, group_id=group_id,
                            )
                        )

        return results

    # ============================== extractFuncName（迁移并健壮化） ==============================

    @staticmethod
    def _extract_func_name(signature: str) -> str:
        """
        从函数签名中提取函数名。

        旧 Java 版本用 \\s+(\\w+)\\s*\\( 取第一个"空白+标识符+(" 的标识符，
        但对关键字型函数（def foo() / function foo()）会误把关键字本身当函数名
        （因为关键字后紧跟空格再接 name(，\\s+(\\w+)\\s*\\( 命中的是 "foo"，OK；
        但对 `public void foo(` 会取到 "foo" 也 OK；对 `func (r Receiver) foo(`
        会取到 "Receiver" —— 但这种 Go receiver 签名本身不在覆盖范围内，可接受）。

        健壮化：先尝试匹配"关键字/修饰符 + name(" 模式，定位真正的函数名标识符；
        退回旧 \\s+(\\w+)\\s*\\( 兜底；都失败则压缩空白取前 30 字符。
        """
        # 1. 关键字引导：def/fn/func/function/fun name( → 取关键字后的标识符
        m = _KEYWORD_FUNC_NAME_PATTERN.search(signature)
        if m:
            return m.group(1)
        # 2. 修饰符引导：取紧邻 ( 的标识符（函数名）—— 即最后一个 \w+ 后接 (
        m = _FUNC_NAME_PATTERN.search(signature)
        if m:
            return m.group(1)
        # 3. 兜底：压缩空白后取前 30 字符
        compact = re.sub(r"\s+", " ", signature)
        return compact[: min(30, len(compact))]

    # ============================== 超长块拆分（本次改造新增） ==============================

    def _split_oversized_block(
        self, block_text: str, threshold: int
    ) -> List[str]:
        """
        拆分超长代码块为子 Node 文本列表。
        优先级：空行/方法边界 → 句子分隔 → 固定行数兜底。
        子 Node 由调用方标注同一 groupId 标识同源整块。

        与旧 Java RecursiveTextSplitter.refine 的区别：
        - 不做 overlap
        - 优先保留代码逻辑边界（空行 = 方法/字段间分隔）
        """
        # 1. 优先按空行拆分（代码中空行通常是逻辑块边界）
        if _BLANK_LINE_SEP.search(block_text):
            segments = _BLANK_LINE_SEP.split(block_text)
            segments = [s for s in segments if s and s.strip()]
            # 若所有段都 ≤ threshold，直接返回；否则对超长段继续按句子/行数兜底
            if all(len(s) <= threshold for s in segments):
                return segments
            # 有超长段，递归拆分（按句子/行数）
            result: List[str] = []
            for seg in segments:
                if len(seg) <= threshold:
                    result.append(seg)
                else:
                    result.extend(self._split_by_sentence_or_lines(seg, threshold))
            return result

        # 2. 无空行 → 按句子/行数兜底
        return self._split_by_sentence_or_lines(block_text, threshold)

    def _split_by_sentence_or_lines(
        self, text: str, threshold: int
    ) -> List[str]:
        """
        句子分隔兜底（[。！？.!?；;]），累加到阈值切；
        无句子分隔则按固定行数（_FALLBACK_LINES_PER_SUB）切。
        """
        # 1. 尝试按句子分隔符拆分
        if _SENTENCE_DELIMITER.search(text):
            sentences = self._split_into_sentences(text)
            results: List[str] = []
            buffer = ""
            for sentence in sentences:
                if not sentence.strip():
                    continue
                if buffer and len(buffer) + len(sentence) > threshold:
                    results.append(buffer)
                    buffer = sentence
                else:
                    buffer += sentence
            if buffer:
                results.append(buffer)
            return results

        # 2. 无句子分隔 → 按固定行数切（保留代码行完整性，不截断行）
        lines = text.split("\n")
        results = []
        buffer_lines: List[str] = []
        buffer_len = 0
        for line in lines:
            line_len = len(line) + 1  # +1 for \n
            if buffer_lines and buffer_len + line_len > threshold:
                results.append("\n".join(buffer_lines))
                buffer_lines = [line]
                buffer_len = line_len
            else:
                buffer_lines.append(line)
                buffer_len += line_len
            # 固定行数兜底（避免某些语言单行极长但仍无分隔符）
            if len(buffer_lines) >= _FALLBACK_LINES_PER_SUB:
                results.append("\n".join(buffer_lines))
                buffer_lines = []
                buffer_len = 0
        if buffer_lines:
            results.append("\n".join(buffer_lines))
        return results

    @staticmethod
    def _split_into_sentences(text: str) -> List[str]:
        """按句子分隔符拆分，保留分隔符在句末。"""
        sentences: List[str] = []
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

    # ============================== 语言推断 ==============================

    @staticmethod
    def _detect_language(file_path: Path, text: str) -> Optional[str]:
        """
        语言推断：优先扩展名映射，否则 CODE_INDICATOR 启发。
        （复用 parser.py._detect_language 的启发思路，扩展为扩展名优先）
        """
        # 1. 优先扩展名映射
        suffix = file_path.suffix.lower().lstrip(".")
        if suffix in _EXT_LANGUAGE_MAP:
            return _EXT_LANGUAGE_MAP[suffix]

        # 2. CODE_INDICATOR 启发（取前 500 字符，与 Java supports 一致）
        if text:
            sample = text[:500]
            if CODE_INDICATOR.search(sample):
                # 复用 parser.py._detect_language 的关键字启发
                head = sample[:200]
                if "public class" in head or "System.out" in head:
                    return "java"
                if "def " in head or "import " in head:
                    return "python"
                if "function " in head or "var " in head or "const " in head:
                    return "javascript"
                if "SELECT " in head.upper() or "FROM " in head.upper():
                    return "sql"
                if "fn " in head:
                    return "rust"
                if "func " in head:
                    return "go"
        return None

    # ============================== 内容特征判定（supports 等价） ==============================

    @staticmethod
    def supports_extension(file_path: str) -> bool:
        """扩展名判定（与 Java CODE_EXTENSIONS.contains 一致）"""
        suffix = Path(file_path).suffix.lower().lstrip(".")
        return suffix in CODE_EXTENSIONS

    @staticmethod
    def supports_content(text: str) -> bool:
        """
        内容特征判定（与 Java CODE_INDICATOR.matcher(sample).find 一致）。
        供路由层 supports 二次判定使用。
        """
        if not text:
            return False
        sample = text[:500]
        return bool(CODE_INDICATOR.search(sample))

    # ============================== Node 构造辅助 ==============================

    @staticmethod
    def _make_node_seq():
        """自管递增 Node id 生成器：n1, n2, ..."""
        i = 0
        while True:
            i += 1
            yield f"n{i}"

    @staticmethod
    def _make_code_node(
        node_id: str,
        text: str,
        language: Optional[str],
        title_path: Optional[str],
        group_id: Optional[str],
    ) -> dict:
        """
        构造一个 code Node dict（与 Java 侧 NodeDTO 协议对应）。
        代码文件无页面/bbox/html 信息。
        group_id 非空表示该 Node 属于「同源整块」拆出的小 Node（超长类/函数块语义切分产物），
        Java 侧据此聚同组 Node 优先组装并合成父子 chunk；普通块 group_id=None。
        """
        return {
            "id": node_id,
            "type": "code",
            "text": text,
            "language": language,
            "titlePath": title_path,
            "groupId": group_id,
            "page": 1,
            "bbox": None,
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
        logger.warning(
            "CodeParser 无法以常见编码解码 %s，回退 latin-1: %s",
            file_path, last_err,
        )
        with open(file_path, "r", encoding="latin-1") as f:
            return f.read()
