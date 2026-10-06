#!/usr/bin/env bash
# Converts a Hugging Face Whisper checkpoint to the GGML format the app's on-device engine (whisper.cpp) loads,
# quantises it, and records exactly what was used so the result can be reproduced and audited.
#
#   HF_REVISION=<commit hash> tools/asr/convert_nepali_to_ggml.sh
#
# REQUIRED  HF_REVISION  exact commit of the model repository. There is deliberately no default: check the model page
#                        (licence, files, commit) yourself and pin it. The build environment that wrote this script could
#                        not reach huggingface.co, so nothing about the repository has been verified.
# Optional  HF_REPO      default sumanpaudel1997/nepali-asr-whisper-turbo
#           QUANT_TYPES  default "q5_0" (also try "q8_0"; then compare accuracy with tools/asr/eval_wer.py)
#           WORK         scratch directory (default ./build/asr-convert)
#           WHISPER_TAG  whisper.cpp release, must match asr-native/src/main/cpp/CMakeLists.txt (default v1.8.3)
#           OPENAI_WHISPER_REF  git ref of github.com/openai/whisper used only for its mel filter table (default main; recorded)
#
# Needs: git, git-lfs, python3 (with venv), cmake, a C++ compiler, ~10 GB of disk, network access.
# Output: $WORK/out/ggml-nepali-whisper-turbo-<type>.bin  and  $WORK/out/conversion-manifest.json
set -euo pipefail

HF_REPO="${HF_REPO:-sumanpaudel1997/nepali-asr-whisper-turbo}"
: "${HF_REVISION:?Set HF_REVISION to the exact commit hash of $HF_REPO after checking its model page and licence}"
QUANT_TYPES="${QUANT_TYPES:-q5_0}"
WHISPER_TAG="${WHISPER_TAG:-v1.8.3}"
OPENAI_WHISPER_REF="${OPENAI_WHISPER_REF:-main}"
WORK="${WORK:-$(pwd)/build/asr-convert}"
OUT="$WORK/out"

for tool in git git-lfs python3 cmake; do command -v "$tool" >/dev/null || { echo "missing tool: $tool" >&2; exit 1; }; done
mkdir -p "$WORK" "$OUT"
cd "$WORK"

echo "== 1/6 sources"
[ -d whisper.cpp ] || git clone --depth 1 --branch "$WHISPER_TAG" https://github.com/ggml-org/whisper.cpp.git whisper.cpp
[ -d openai-whisper ] || git clone --depth 1 --branch "$OPENAI_WHISPER_REF" https://github.com/openai/whisper.git openai-whisper
git lfs install --skip-repo >/dev/null
[ -d model-src ] || git clone "https://huggingface.co/$HF_REPO" model-src
git -C model-src checkout --quiet "$HF_REVISION"
ACTUAL_REV="$(git -C model-src rev-parse HEAD)"
echo "model revision: $ACTUAL_REV"

echo "== 2/6 the checkpoint has what the converter needs"
for f in config.json vocab.json added_tokens.json; do
  [ -f "model-src/$f" ] || { echo "model-src/$f is missing: the converter needs a Hugging Face (transformers) Whisper checkpoint" >&2; exit 1; }
done
[ -f model-src/model.safetensors ] || [ -f model-src/pytorch_model.bin ] || { echo "no model.safetensors / pytorch_model.bin in model-src (is git-lfs working?)" >&2; exit 1; }
LICENSE_LINE="$(grep -iE '^license:' model-src/README.md 2>/dev/null | head -1 || true)"
echo "licence line in the model card: ${LICENSE_LINE:-<none found: check the model page>}"

echo "== 3/6 python environment"
[ -d venv ] || python3 -m venv venv
# venv's activate script is not written for `set -u`
set +u
# shellcheck disable=SC1091
. venv/bin/activate
set -u
pip install --quiet --upgrade pip
pip install --quiet torch transformers numpy safetensors   # versions are recorded in the manifest below

echo "== 4/6 convert to GGML (f16)"
python3 whisper.cpp/models/convert-h5-to-ggml.py model-src openai-whisper "$OUT"
F16="$OUT/ggml-model.bin"
[ -f "$F16" ] || { echo "converter did not produce $F16" >&2; exit 1; }
mv "$F16" "$OUT/ggml-nepali-whisper-turbo-f16.bin"
F16="$OUT/ggml-nepali-whisper-turbo-f16.bin"

echo "== 5/6 build whisper.cpp tools (quantiser + CLI used for validation)"
cmake -S whisper.cpp -B whisper.cpp/build -DCMAKE_BUILD_TYPE=Release -DWHISPER_BUILD_EXAMPLES=ON -DWHISPER_BUILD_TESTS=OFF >/dev/null
cmake --build whisper.cpp/build --target whisper-quantize whisper-cli -j "$(nproc 2>/dev/null || echo 4)"
BIN="whisper.cpp/build/bin"

echo "== 6/6 quantise"
for q in $QUANT_TYPES; do
  "$BIN/whisper-quantize" "$F16" "$OUT/ggml-nepali-whisper-turbo-$q.bin" "$q"
done

echo "== manifest"
python3 - "$OUT" "$HF_REPO" "$ACTUAL_REV" "$WHISPER_TAG" "$OPENAI_WHISPER_REF" "$LICENSE_LINE" <<'PY'
import hashlib, json, os, subprocess, sys
out, repo, rev, tag, oref, lic = sys.argv[1:7]
def sha(p):
    h = hashlib.sha256()
    with open(p, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()
def git(path):
    return subprocess.run(["git", "-C", path, "rev-parse", "HEAD"], capture_output=True, text=True).stdout.strip()
import importlib.metadata as md
manifest = {
    "model": {"repo": repo, "revision": rev, "licence_line_in_model_card": lic or None},
    "whisper_cpp": {"tag": tag, "commit": git("whisper.cpp")},
    "openai_whisper": {"ref": oref, "commit": git("openai-whisper"), "used_for": "mel filter table only"},
    "python": {"version": sys.version.split()[0], **{p: md.version(p) for p in ("torch", "transformers", "numpy", "safetensors")}},
    "files": [
        {"name": n, "size_bytes": os.path.getsize(os.path.join(out, n)), "sha256": sha(os.path.join(out, n))}
        for n in sorted(os.listdir(out)) if n.endswith(".bin")
    ],
}
path = os.path.join(out, "conversion-manifest.json")
json.dump(manifest, open(path, "w"), indent=2)
print(json.dumps(manifest, indent=2))
PY

cat <<MSG

Done. Next:
  1. VALIDATE accuracy before trusting a quantised file (compare against the f16 file on held-out Nepali audio):
       python3 tools/asr/eval_wer.py --cli $WORK/$BIN/whisper-cli --audio-dir <dir with .wav + .txt> \\
           --model $OUT/ggml-nepali-whisper-turbo-f16.bin --model $OUT/ggml-nepali-whisper-turbo-q5_0.bin
  2. Put the chosen file on the phone (see docs/ON_DEVICE_ASR.md, "Installing the model") and use Settings > Import model file.
  3. To enable in-app download later: host the file, then fill url + sha256 (+ revision) for it in
     speech/src/main/resources/asr/models.json.
MSG
