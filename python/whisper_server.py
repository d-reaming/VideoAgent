import argparse
import json
import sys

"""
持久化 faster-whisper 转写 worker（常驻进程）：
- 启动时加载模型一次，之后通过 stdin 逐行接收 JSON 请求，stdout 逐行回 JSON 响应。
- 协议（JSON-lines）：
    请求  {"id":<int>, "audio":<path>, "language":<code>}
    响应  {"id":<int>, "segments":[{"start":s,"end":e,"text":t}, ...]}
    异常  {"id":<int>, "error":<msg>}
- 只在 stdout 打印响应 JSON；进程自身日志统一走 stderr，避免污染协议。
用法: python whisper_server.py [--model small] [--device cpu] [--language zh]
"""


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", default="small", help="模型档位: tiny/base/small/medium/large-v3")
    parser.add_argument("--device", default="cpu", help="auto/cpu/cuda")
    parser.add_argument("--language", default=None, help="默认语言代码(缺省 None=自动识别)")
    args = parser.parse_args()

    print(f"[whisper-server] loading model={args.model} device={args.device} ...",
          file=sys.stderr, flush=True)
    from faster_whisper import WhisperModel
    model = WhisperModel(args.model, device=args.device, compute_type="int8")
    print("[whisper-server] model ready, waiting for requests", file=sys.stderr, flush=True)

    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        print(json.dumps(process(model, line, args.language), ensure_ascii=False), flush=True)


def process(model, line, default_language):
    try:
        req = json.loads(line)
        audio = req["audio"]
        # 请求内 language 字段优先；缺失才用默认。null/None 表示 AUTO(自动识别/mixed)
        language = req.get("language", default_language)
        segments, _info = model.transcribe(
            audio,
            language=language,
            vad_filter=True,
            vad_parameters={"min_silence_duration_ms": 500},
        )
        segs = [
            {"start": round(s.start, 2), "end": round(s.end, 2), "text": s.text.strip()}
            for s in segments
        ]
        return {"id": req.get("id"), "segments": segs}
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