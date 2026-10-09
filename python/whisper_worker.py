import argparse
import json
import sys

"""
faster-whisper 转写 worker：供 Java 端 ProcessBuilder 调用。
输出: stdout 打印 JSON 数组 [{start,end,text}]，start/end 为秒。
用法: python whisper_worker.py --audio <path> [--model small] [--device auto] [--language zh]
"""


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--audio", required=True, help="音频文件路径(wav)")
    parser.add_argument("--model", default="small", help="模型档位: tiny/base/small/medium/large-v3")
    parser.add_argument("--device", default="auto", help="auto/cpu/cuda")
    parser.add_argument("--language", default="zh", help="语言代码")
    args = parser.parse_args()

    from faster_whisper import WhisperModel

    model = WhisperModel(args.model, device=args.device, compute_type="int8")
    segments, _info = model.transcribe(
        args.audio,
        language=args.language,
        vad_filter=True,
        vad_parameters={"min_silence_duration_ms": 500},
    )
    result = [
        {"start": round(seg.start, 2), "end": round(seg.end, 2), "text": seg.text.strip()}
        for seg in segments
    ]
    print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    try:
        main()
    except Exception as e:  # noqa: BLE001
        print(json.dumps({"error": str(e)}, ensure_ascii=False), file=sys.stderr)
        sys.exit(1)
