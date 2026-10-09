import argparse
import json
import re
import sys

"""
持久化 RapidOCR 文字识别 worker（常驻进程）：
- 启动时加载 RapidOCR 模型一次，之后通过 stdin 逐行接收 JSON 请求，stdout 逐行回 JSON 响应。
- 协议（JSON-lines，与 whisper_server.py 一致）：
    请求  {"id":<int>, "image":<path>}
    响应  {"id":<int>, "items":[{"text":<str>, "confidence":<float>, "box":[[x,y],...]}, ...]}
    异常  {"id":<int>, "error":<msg>}
- 只在 stdout 打印响应 JSON；进程自身日志统一走 stderr，避免污染协议。
- 过滤策略：置信度 >= min_confidence，去空白，去纯标点/无意义单字符噪点。
用法: python ocr_server.py [--min-confidence 0.5]
"""

_PUNCT_ONLY = re.compile(r"^[\s\W_]+$", re.UNICODE)


def _clean(text):
    t = text.strip()
    if not t:
        return None
    if _PUNCT_ONLY.match(t):
        return None
    return t


def _filter_items(items, min_confidence):
    out = []
    for item in items:
        try:
            box, text, score = item
            if score < min_confidence:
                continue
            t = _clean(text)
            if not t:
                continue
            # 未知字符乱码片段（PaddleOCR 对不识别的字形输出 '?'）直接丢弃，避免低质噪声入库
            if "?" in t:
                continue
            out.append({"text": t, "confidence": round(float(score), 3),
                        "box": [[float(x), float(y)] for x, y in box]})
        except Exception:  # noqa: BLE001
            continue
    return out


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--min-confidence", type=float, default=0.5,
                        help="OCR 置信度阈值(0~1)，低于此值的识别结果丢弃")
    parser.add_argument("--device", default="cpu", help="cpu / gpu（RapidOCR 仅 CPU 可用时忽略）")
    args = parser.parse_args()

    print(f"[ocr-server] loading RapidOCR device={args.device} min_confidence={args.min_confidence} ...",
          file=sys.stderr, flush=True)
    from rapidocr_onnxruntime import RapidOCR
    ocr = RapidOCR()
    print("[ocr-server] model ready, waiting for requests", file=sys.stderr, flush=True)

    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        # 与 whisper_server.py 一致：ensure_ascii=True（\uXXXX 转义，纯 ASCII），
        # 规避 Windows 管道 GBK 输出被 Java UTF-8 解码成乱码的问题
        print(json.dumps(process(ocr, line, args.min_confidence)), flush=True)


def process(ocr, line, min_confidence):
    try:
        req = json.loads(line)
        image = req["image"]
        result, _elapse = ocr(image)
        items = _filter_items(result or [], min_confidence)
        return {"id": req.get("id"), "items": items}
    except Exception as e:  # noqa: BLE001
        req_id = None
        try:
            req_id = json.loads(line).get("id")
        except Exception:  # noqa: BLE001
            pass
        return {"id": req_id, "error": str(e)}


if __name__ == "__main__":
    try:
        main()
    except Exception as e:  # noqa: BLE001
        print(json.dumps({"error": str(e)}, ensure_ascii=False), file=sys.stderr)
        sys.exit(1)
