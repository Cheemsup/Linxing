"""
MinerU 云托管 API 客户端

对接官方 v4 云 API（https://mineru.net/apiManage/docs），Bearer token 鉴权。
本地文件提取流程（异步：提交 → 轮询 → 下载产物）：

1. POST {base}/api/v4/file-urls/batch       申请上传 URL
   body: {"files": [{"name", "data_id"}], "model_version"}   → data.batch_id + data.file_urls[0]
2. PUT  {file_urls[0]}                       上传原始文件字节（上传完成即自动提交解析）
3. GET  {base}/api/v4/extract-results/batch/{batch_id}  轮询至 state=done
   → extract_result 条目（按 data_id 匹配）含 full_zip_url
4. GET  {full_zip_url} 下载结果压缩包并解压
   内含 full.md（Markdown）/ images/（提取图片）/ *_content_list.json（结构化内容列表）
   / *_middle.json（含 pdf_info）/ *_model.json

Python 侧解析服务职责（parsers/pdf_parser.py）：
- 主路径：读 *_content_list.json 结构化内容列表 → Node JSON（保 page/bbox/formula）
- 兜底路径：content_list 缺失时读 full.md → MarkdownParser
- 最终兜底：云端失败/未配置 key → 本地 PyMuPDF（_parse_legacy）
"""

import logging
import tempfile
import time
import zipfile
from pathlib import Path
from typing import Dict, List, Optional, Tuple

import requests

logger = logging.getLogger("docling_analysis_service.parsers.mineru_client")


class MineruError(Exception):
    """MinerU 云解析异常（含后端返回的 err_msg / code / msg）。"""


class MineruClient:
    """MinerU 云托管解析客户端（Bearer token 鉴权，单文件提取）。"""

    def __init__(
        self,
        api_key: str,
        base_url: str = "https://mineru.net",
        model_version: str = "vlm",
        poll_interval: int = 3,
        timeout_seconds: int = 480,
        http_timeout: int = 60,
    ):
        """
        :param api_key: 在 MinerU API 管理页创建的 token
        :param base_url: API 基地址（默认 https://mineru.net，实测 api.mineru.net 不可达）
        :param model_version: 模型版本（文档用 vlm，HTML 用 MinerU-HTML）
        :param poll_interval: 结果轮询间隔（秒）
        :param timeout_seconds: 云端处理总超时（上传+轮询+下载）
        :param http_timeout: 单次 HTTP 请求读超时（秒）
        """
        if not api_key:
            raise ValueError("MineruClient 需要 api_key")
        self.api_key = api_key
        self.base_url = base_url.rstrip("/")
        self.model_version = model_version
        self.poll_interval = poll_interval
        self.timeout_seconds = timeout_seconds
        self.http_timeout = http_timeout
        self._auth_headers = {"Authorization": f"Bearer {api_key}"}

    # ============================== 对外入口 ==============================

    def extract(self, file_path: str, data_id: str) -> Path:
        """
        执行一次完整提取，返回解压后的结果目录（目录生命周期由调用方持有，不自动清理）。

        :param file_path: 待解析文件本地路径
        :param data_id: 业务数据 ID（用于结果回匹配，传 document_id 即可）
        :return: 解压后的结果目录 Path
        :raises MineruError: 申请 URL / 上传 / 轮询 / 下载任一环节失败
        """
        file_path = Path(file_path)
        file_name = file_path.name
        logger.info("MinerU 开始提取: %s (data_id=%s, model=%s)",
                    file_path, data_id, self.model_version)

        # 1. 申请上传 URL
        batch_id, file_url = self._apply_upload_url(file_name, data_id)
        logger.info("MinerU 上传 URL 已申请: batch_id=%s", batch_id)

        # 2. 上传裸字节（上传完成自动提交解析）
        self._upload_file(file_url, file_path)

        # 3. 轮询结果
        result = self._poll(batch_id, data_id)
        zip_url = result.get("full_zip_url")
        if not zip_url:
            raise MineruError(
                f"MinerU 任务完成但缺少 full_zip_url: batch_id={batch_id}, data_id={data_id}"
            )

        # 4. 下载并解压
        extract_dir = self._download_and_unzip(zip_url, file_name)
        logger.info("MinerU 提取完成并解压: %s", extract_dir)
        return extract_dir

    # ============================== 各环节 ==============================

    def _apply_upload_url(self, file_name: str, data_id: str) -> Tuple[str, str]:
        """
        POST /api/v4/file-urls/batch 申请上传 URL。

        :return: (batch_id, file_url)
        """
        url = f"{self.base_url}/api/v4/file-urls/batch"
        payload = {
            "files": [{"name": file_name, "data_id": str(data_id)}],
            "model_version": self.model_version,
        }
        try:
            resp = requests.post(
                url, json=payload, headers=self._auth_headers, timeout=self.http_timeout
            )
        except requests.RequestException as e:
            raise MineruError(f"申请 MinerU 上传 URL 网络失败: {e}") from e

        if resp.status_code != 200:
            raise MineruError(
                f"申请 MinerU 上传 URL 失败: HTTP {resp.status_code}, body={resp.text[:500]}"
            )
        body = resp.json()
        if body.get("code") != 0:
            raise MineruError(f"申请 MinerU 上传 URL 失败: code={body.get('code')}, msg={body.get('msg')}")

        data = body.get("data") or {}
        batch_id = data.get("batch_id")
        file_urls = data.get("file_urls") or []
        if not batch_id or not file_urls:
            raise MineruError(f"申请 MinerU 上传 URL 响应缺少 batch_id/file_urls: {body}")
        return batch_id, file_urls[0]

    def _upload_file(self, file_url: str, file_path: Path) -> None:
        """PUT 上传原始文件字节到 OSS（上传完成即自动提交解析）。"""
        try:
            with open(file_path, "rb") as f:
                resp = requests.put(
                    file_url, data=f, timeout=self.http_timeout
                )
        except requests.RequestException as e:
            raise MineruError(f"MinerU 文件上传网络失败: {e}") from e
        if resp.status_code not in (200, 201):
            raise MineruError(
                f"MinerU 文件上传失败: HTTP {resp.status_code}, body={resp.text[:500]}"
            )

    def _poll(self, batch_id: str, data_id: str) -> Dict:
        """
        轮询 GET /api/v4/extract-results/batch/{batch_id} 直至 done/failed。

        :return: 匹配 data_id 的 extract_result 条目
        :raises MineruError: failed 状态 / 超时 / 网络异常
        """
        url = f"{self.base_url}/api/v4/extract-results/batch/{batch_id}"
        start = time.monotonic()

        while True:
            if time.monotonic() - start > self.timeout_seconds:
                raise MineruError(
                    f"MinerU 解析超时（>{self.timeout_seconds}s）: batch_id={batch_id}, data_id={data_id}"
                )
            try:
                resp = requests.get(url, headers=self._auth_headers, timeout=self.http_timeout)
            except requests.RequestException as e:
                raise MineruError(f"轮询 MinerU 结果网络失败: {e}") from e
            if resp.status_code != 200:
                raise MineruError(
                    f"轮询 MinerU 结果失败: HTTP {resp.status_code}, body={resp.text[:500]}"
                )
            body = resp.json()
            if body.get("code") != 0:
                raise MineruError(f"轮询 MinerU 结果失败: code={body.get('code')}, msg={body.get('msg')}")

            extract_results = (body.get("data") or {}).get("extract_result") or []
            # 优先按 data_id 匹配；匹配不到则取首个（单文件批次）
            result = next(
                (r for r in extract_results if r.get("data_id") == str(data_id)),
                extract_results[0] if extract_results else None,
            )
            if result is None:
                logger.debug("MinerU 批次结果暂未就绪（data_id=%s），%.0fs", data_id,
                             time.monotonic() - start)
            else:
                state = result.get("state")
                if state == "done":
                    return result
                if state == "failed":
                    err = result.get("err_msg") or "未知错误"
                    raise MineruError(f"MinerU 解析失败: {err} (batch_id={batch_id})")
                # pending/running/waiting-file/converting 继续等待
                logger.info("MinerU 解析中状态=%s（%.0fs）", state, time.monotonic() - start)

            time.sleep(self.poll_interval)

    def _download_and_unzip(self, zip_url: str, file_name: str) -> Path:
        """下载结果 zip 并解压到独立临时目录，返回该目录。"""
        try:
            resp = requests.get(zip_url, timeout=self.http_timeout)
        except requests.RequestException as e:
            raise MineruError(f"下载 MinerU 结果 zip 网络失败: {e}") from e
        if resp.status_code != 200:
            raise MineruError(
                f"下载 MinerU 结果 zip 失败: HTTP {resp.status_code}"
            )

        # 解压到临时目录（命名带原文件名前缀便于排查）
        extract_dir = Path(tempfile.mkdtemp(prefix=f"mineru_{Path(file_name).stem}_"))
        try:
            with tempfile.NamedTemporaryFile(suffix=".zip", delete=False) as tmp_zip:
                tmp_zip.write(resp.content)
                zip_path = tmp_zip.name
            with zipfile.ZipFile(zip_path) as zf:
                zf.extractall(extract_dir)
        except zipfile.BadZipFile as e:
            raise MineruError(f"MinerU 结果 zip 损坏: {e}") from e
        finally:
            # 清理临时 zip 文件
            try:
                Path(zip_path).unlink(missing_ok=True)
            except Exception:
                pass

        return extract_dir

    # ============================== 产物定位辅助 ==============================

    @staticmethod
    def find_content_list(extract_dir: Path) -> Optional[Path]:
        """
        在解压目录中定位结构化内容列表 JSON。

        优先 *_content_list.json（v1，schema 字段稳定：type/text/text_level/img_path/
        table_body/code_body/list_items/page_idx/bbox），排除 *_v2.json（schema 变动大）
        与 *_middle.json / *_model.json。
        """
        candidates = sorted(
            p for p in extract_dir.rglob("*_content_list.json")
            if "_v2." not in p.name
        )
        return candidates[0] if candidates else None

    @staticmethod
    def find_markdown(extract_dir: Path) -> Optional[Path]:
        """定位 full.md（兜底用）；无则取首个 *.md。"""
        candidates = [p for p in extract_dir.rglob("*.md") if p.name.lower() == "full.md"]
        if candidates:
            return candidates[0]
        md_files = sorted(p for p in extract_dir.rglob("*.md"))
        return md_files[0] if md_files else None