#!/usr/bin/env python3
"""Measures Nepali recognition accuracy (word and character error rate) of GGML Whisper models with whisper-cli.

Use it to compare a quantised model against its f16 original BEFORE relying on the quantised one.

  python3 tools/asr/eval_wer.py --cli path/to/whisper-cli --audio-dir tests/nepali \
      --model ggml-nepali-whisper-turbo-f16.bin --model ggml-nepali-whisper-turbo-q5_0.bin

The audio directory holds pairs  name.wav  (16 kHz mono; convert with: ffmpeg -i in.m4a -ar 16000 -ac 1 name.wav)
and  name.txt  (the reference transcript). Standard library only. The error-rate maths is unit-tested
(tools/asr/test_eval_wer.py).
"""
import argparse
import os
import re
import subprocess
import sys
import time
import unicodedata

DEVANAGARI_DIGITS = str.maketrans("०१२३४५६७८९", "0123456789")
# ASCII punctuation, the Devanagari danda/double danda, and typographic quotes/ellipsis.
PUNCT = re.compile(r"[!-/:-@\[-`{-~।॥‘-‟…]+")


def normalize(text: str) -> str:
    """Comparison form: NFC, Devanagari digits as ASCII, no punctuation, lower-case Latin, single spaces."""
    t = unicodedata.normalize("NFC", text).translate(DEVANAGARI_DIGITS).lower()
    return re.sub(r"\s+", " ", PUNCT.sub(" ", t)).strip()


def edit_distance(a, b) -> int:
    """Levenshtein distance between two sequences."""
    if not a:
        return len(b)
    prev = list(range(len(b) + 1))
    for i, x in enumerate(a, 1):
        cur = [i]
        for j, y in enumerate(b, 1):
            cur.append(min(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + (x != y)))
        prev = cur
    return prev[-1]


def wer(ref: str, hyp: str) -> tuple:
    """(errors, reference words) on normalised text."""
    r, h = normalize(ref).split(), normalize(hyp).split()
    return edit_distance(r, h), len(r)


def cer(ref: str, hyp: str) -> tuple:
    """(errors, reference characters), spaces ignored."""
    r, h = normalize(ref).replace(" ", ""), normalize(hyp).replace(" ", "")
    return edit_distance(r, h), len(r)


def transcribe(cli: str, model: str, wav: str, language: str, threads: int) -> tuple:
    t0 = time.time()
    p = subprocess.run([cli, "-m", model, "-f", wav, "-l", language, "-nt", "-t", str(threads)], capture_output=True, text=True)
    if p.returncode != 0:
        raise RuntimeError(f"whisper-cli failed on {wav}: {p.stderr[-300:]}")
    return " ".join(p.stdout.split()), time.time() - t0


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--cli", required=True, help="path to whisper-cli")
    ap.add_argument("--audio-dir", required=True)
    ap.add_argument("--model", action="append", required=True, help="GGML model; repeat to compare (first = baseline)")
    ap.add_argument("--language", default="ne")
    ap.add_argument("--threads", type=int, default=6)
    ap.add_argument("--max-degradation", type=float, default=None,
                    help="fail (exit 1) if any later model's WER exceeds the first model's by more than this (e.g. 0.02 = 2 points)")
    a = ap.parse_args()

    pairs = sorted((os.path.join(a.audio_dir, f), os.path.join(a.audio_dir, f[:-4] + ".txt"))
                   for f in os.listdir(a.audio_dir) if f.endswith(".wav") and os.path.exists(os.path.join(a.audio_dir, f[:-4] + ".txt")))
    if not pairs:
        print("no name.wav + name.txt pairs found", file=sys.stderr)
        return 2

    results = []
    for model in a.model:
        w_err = w_tot = c_err = c_tot = 0
        audio_s = infer_s = 0.0
        for wav, txt in pairs:
            ref = open(txt, encoding="utf-8").read()
            hyp, took = transcribe(a.cli, model, wav, a.language, a.threads)
            e, n = wer(ref, hyp)
            ce, cn = cer(ref, hyp)
            w_err += e; w_tot += n; c_err += ce; c_tot += cn
            infer_s += took
            print(f"  {os.path.basename(model)}  {os.path.basename(wav)}  WER {e}/{n}  hyp: {hyp[:80]}")
        results.append((model, w_err / max(w_tot, 1), c_err / max(c_tot, 1), infer_s))

    print("\nmodel                                          WER      CER      time (s, incl. model load per file)")
    for m, w, c, t in results:
        print(f"{os.path.basename(m):45s}  {w:6.1%}  {c:6.1%}  {t:8.1f}")
    if a.max_degradation is not None and len(results) > 1:
        base = results[0][1]
        bad = [os.path.basename(m) for m, w, _, _ in results[1:] if w - base > a.max_degradation]
        if bad:
            print(f"\nFAIL: WER of {', '.join(bad)} is more than {a.max_degradation:.1%} worse than the baseline", file=sys.stderr)
            return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
