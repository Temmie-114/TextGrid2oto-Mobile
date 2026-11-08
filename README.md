# TextGrid2oto Mobile

An Android port of [xiaobaijunya/TextGrid2oto](https://github.com/xiaobaijunya/TextGrid2oto) (desktop wxPython tool).
It runs the whole **wav → lab → forced alignment → TextGrid → json → oto.ini** chain on a phone.

> 中文文档：[`README.zh-CN.md`](README.zh-CN.md)

---

## 0. Upstream & License

> **This project is a derivative work of [xiaobaijunya/TextGrid2oto](https://github.com/xiaobaijunya/TextGrid2oto) and is released under GPL-3.0 as well.**

| Component | Upstream | License | How this repo handles it |
|---|---|---|---|
| Ported codebase | [xiaobaijunya/TextGrid2oto](https://github.com/xiaobaijunya/TextGrid2oto) | **GPL-3.0** | Python decoding chain adapted for mobile; the whole project stays GPL-3.0 |
| TIFA aligner | [openvpi/TIFA](https://github.com/openvpi/TIFA) (`TIFA-1.0-ST`) | **MIT** | PyTorch weights exported to ONNX by us, then weight-level fp16 — a **modified version** |
| HubertFA aligner | [wolfgitpr/HubertFA](https://github.com/wolfgitpr/HubertFA) (`1218_hfa_model` v0.0.7) | **Apache-2.0** | Weight-level fp16 conversion only — a **modified version** |
| HFA acoustic backbone | [TencentGameMate/chinese-hubert-base](https://huggingface.co/TencentGameMate/chinese-hubert-base) | **MIT** | Redistributed as part of the HubertFA checkpoint |
| UI icons | Google Material Symbols | **Apache-2.0** | Embedded as vector drawables |
| App icon | Derived from `img/TextGrid2oto.ico` in the upstream repo | Follows upstream GPL-3.0 | Converted to `mipmap` densities + adaptive icon |

MIT and Apache-2.0 are both one-way compatible with GPLv3, so shipping everything under GPL-3.0 is valid.
Modification statements are in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md) and in section 8 below (GPL-3.0 §5).

---

## 1. Features

| Tab | Upstream stage | What it does |
|---|---|---|
| ① Annotate | `lab_generate` | Builds a `.lab` from each wav filename (pinyin / kana / romaji); supports delimiter splitting, auto `SP`, dropping `R`, index-table mode |
| ② Align | `inference/onnx_infer.py`, `inference/tifa_infer.py` | HubertFA / TIFA forced alignment, writes a TextGrid with `words` and `phones` tiers |
| ③ JSON | `textgrid2json/` | TextGrid → `ds_phone.json` → filtered by dictionary → rebuilt `word_phone.json` |
| ④ oto | `json2oto/` | Generates CV / CVVC / VCV / ARPAsing `oto.ini`; template, encoding, repeat count, offsets |
| Tools | `build_diffdataset/` etc. | Model manager, dictionaries, `ph_num`, CSV↔TextGrid, words→lab, loudness check, strip `SP`, DiffSinger dataset, GAME MIDI annotation |

- **Sidebar**: the four workflow tabs, resources (models / dictionaries), tools, settings (appearance, inference backend, interface language), about & licenses.
- **Theme**: light / dark / system, Android 12+ dynamic color, ring color picker (outer ring = hue, inner disc = saturation; lightness comes from the M3 tonal palette and does not follow the seed color).
- **Bottom bar**: progress strip (status + percentage) directly above the run log (collapsible, clearable, auto-scrolling), with the navigation bar below.
- **Localisation**: the sidebar language selector has *Follow system / 中文 / English*, persisted in DataStore. The licenses page is always English (license texts are English; translating them would desync from the originals).

### oto switches that matter

| Switch | Default | Effect |
|---|---|---|
| `Measure cutoff from the end of the file` | off | Off: counted from `offset` per the UTAU spec. On: counted backwards from the audio end |
| `Extend the initial cutoff to the next syllable` | off | When on, the playback range of the initial `- X` entry stretches to meet the first VCV entry, removing the 10–170 ms gap at the start of a phrase; the value ends up longer than the official formula |
| `Initial offset prefetch (ms)` | 0 | Moves the initial entry's `offset` back by N ms and adds the same amount to `preutterance`, so the note head stays put. Alignment often assigns the initial consonant to the preceding `R`/`SP` span, leaving vowel only — try 20–50 |

---

## 2. Architecture

Upstream depends on `onnxruntime`, `librosa`, `soundfile`, `textgrid`, `wxPython`.
There is no onnxruntime Python wheel for Android, and librosa drags in numba/scipy, so the stack is split in two:

```
┌──────────────────────────── Compose UI (Kotlin) ────────────────────────────┐
│  4 workflow tabs / sidebar / theme / progress strip / log panel              │
└───────────────┬──────────────────────────────────────────┬──────────────────┘
                │ Chaquopy (Python 3.12)                    │ ONNX Runtime Android
┌───────────────▼──────────────────────────┐   ┌───────────▼──────────────────┐
│ tg2oto.runner  job orchestration         │   │ OrtEngine (Kotlin)           │
│ tg2oto.inference.*  decode / postproc    │──▶│ loads sessions, runs, caches │
│ tg2oto.lab_generate / textgrid2json ...  │   │ outputs as .bin + manifest   │
└──────────────────────────────────────────┘   └───────────────────────────────┘
```

- **Inference**: HubertFA takes the 16 kHz waveform directly (`waveform [1, n_samples]`), no fbank on the Python side. `AlignmentDecoder` / `NonLexicalDecoder` are pure-numpy Viterbi — the heavy compute is Kotlin ORT, the decoding and file writing stay in Python.
- **Shims**: `tg2oto/ort_shim.py` provides a fake `onnxruntime` module (`InferenceSession` / `SessionOptions` / `NodeArg`) that forwards to `com.tg2oto.mobile.core.OrtEngine`. Tensors travel as "binary file + manifest JSON" to avoid large JNI copies.
- **Audio**: `tg2oto/audio_io.py` implements `load` / `resample` / `midi_to_note` with the standard library `wave` plus numpy; resampling matches `scipy.signal.resample_poly` (44.1k / 48k / 22.05k → 16k, max error ≤ 6.2e-4). `soundfile_shim.py` stands in for `soundfile`.
- Chaquopy only pulls `numpy` and `textgrid`.

### Process isolation

All Python / ONNX work runs in a separate `:infer` process (`InferService`); the main process only draws UI:

```
main process (com.tg2oto.mobile)          :infer process
┌──────────────────────────┐   Messenger  ┌──────────────────────────────┐
│ Compose UI / AppViewModel│  RUN / CANCEL │ InferService                 │
│ log panel / progress     │◀─────────────│ Python (Chaquopy) + ORT      │
└──────────────────────────┘ LOG/PROG/RESULT└────────────────────────────┘
```

- 200 MB of weights, Python's memory peak, native crashes and OOM all stay inside the child process; the UI survives.
- Crash handling: thread / Java exceptions are written to `filesDir/infer_crash.txt` and reported; native crashes and kills are caught by `onServiceDisconnected` in the main process. Both paths show a dialog with the error plus the last 240 log lines, and "Copy log" puts it on the clipboard.
- "Stop" sends `CANCEL`; the Python side polls `_StopEvent` and `JobControl` between steps.

---

## 3. Models

### Bundled (extracted to app-private storage on first launch)

| Model | Source | Original | Bundled |
|---|---|---|---|
| HubertFA `1218_hfa_model` | `wolfgitpr/HubertFA` v0.0.7 | 396 MB (fp32) | **199 MB (weight-level fp16)** |
| TIFA `TIFA-1.0-ST` | TIFA official weights (`openvpi/TIFA`) | 160 MB (fp32) | **78 MB (weight-level fp16)** |

`assets/models/` totals ~277 MB, the APK is ~344 MB, and an install takes ~600 MB (the first launch copies them into `filesDir`).

### On fp16

Whole-graph fp16 conversion (`onnxconverter_common` / `onnxruntime.transformers`) produces an invalid graph on these models (duplicate node names, type mismatches), and the CPU EP lacks most fp16 kernels.
So we do **weight-level fp16**: `initializer` stored as fp16 with a `Cast → fp32` inserted at every consumer; all math stays fp32.

| Precision | Size | Speed (TIFA main graph, measured) | Accuracy impact | Verdict |
|---|---|---|---|---|
| fp32 | 158 MB | 64 ms | baseline | Works, wastes space |
| **fp16 (weight-level)** | **78 MB** | **64 ms (unchanged)** | logits relative error ~1e-2; end-to-end TextGrid boundary delta 0.0000 s | **Recommended — this is what ships** |
| int8 (dynamic) | 41.8 MB | **289 ms (4.5× slower)** | logits relative error 2.08% | Runs but slower — not recommended |

int8 is slower because `DynamicQuantizeLinear` quantise/dequantise overhead on ARM CPU outweighs the saved matmul work, and these graphs (LayerNorm + attention + many reshapes) are not int8-friendly in the first place.

> **Quantise before importing, not during it**: Chaquopy has no usable `onnx` package — PyPI ships only x86 / arm64-mac wheels, and a source build on Android arm64 needs CMake + protobuf. The app therefore only detects precision and loads what you give it. Conversion script: `python3 tools/convert_weight_fp16.py in.onnx out.onnx`.

### Importing custom models

Sidebar → Models → "Import zip". The archive may contain the model folder itself or wrap it in one more level.

**Imported models are listed under the archive's own name** — `_2`, `_3` are appended when one zip holds several models, and only the two bundled models show a type name. On disk they land in `filesDir/models/imported/<zip name>` (suffix added on collision).

Each model folder inside the zip is accepted only if it carries the required files:

| Type | Required files |
|---|---|
| HubertFA | `model.onnx` + `vocab.json` (optional `config.json`, per-language `.txt` dictionaries, `VERSION` = 5) |
| TIFA | `model.onnx` + `spectrogram.onnx` + `vocabulary.json` |
| GAME | `encoder.onnx` + `segmenter.onnx` + `estimator.onnx` + `dur2bd.onnx` + `bd2dur.onnx` + `config.json` |

HubertFA and TIFA are the two aligner families. GAME is **not an aligner** — it takes a `transcriptions.csv` that already carries `ph_seq`, `ph_dur` and `ph_num`, and writes `note_seq` / `note_dur` / `note_slur` (MIDI note annotations) back into it, for DiffSinger dataset labelling. Its weights are **not bundled** — download them from [`openvpi/GAME` releases](https://github.com/openvpi/GAME/releases) and import the folder as a zip; `config.json` is required and ships in the release archive. Audio is looked up at `<csv dir>/wavs/<name>.wav`; **flac is not supported** (convert to 16-bit PCM wav first).

The zip name is a display label only and plays no part in detection: folders without `model.onnx` and its companions are skipped.

**Precision detection** scans the ONNX protobuf directly (reads dtype / dims only, seeks past weights, ~0.6 s for 200 MB) and reports `fp32` / `fp16` / `int8`; the result is cached inside the model folder so the list does not rescan.

---

## 4. Building

```bash
# JDK 17, Android SDK (platform-35 / build-tools 35), Gradle 8.9
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleRelease
# artifact: app/build/outputs/apk/release/app-release.apk
```

- `minSdk 26`, `targetSdk 35`, `arm64-v8a` only (Chaquopy 17 + Python 3.12 is 64-bit only).
- The first build needs network access to fetch the numpy / openblas Android wheels and the Python runtime for Chaquopy.
- **No `*.onnx` weights are committed.** To build your own APK, drop the upstream release models into `app/src/main/assets/models/` with the same layout, or generate the fp16 versions with `tools/convert_weight_fp16.py`.
- **Do not run several Gradle tasks in parallel** — it fails with `Storage ... is already registered`.

---

## 5. Permissions

The app reads and writes `/storage/emulated/0/...` recording folders directly (it works on paths, like the desktop version), so it needs **All files access** (`MANAGE_EXTERNAL_STORAGE`). A banner appears on first launch with a "Grant" button that opens the system settings page.
If you pick folders through SAF instead, the app tries to map `content://` back to a real path; when that fails it copies the files into app-private storage.

---

## 6. Backends

Android has no unified NPU interface, and this version **does not wire up vendor NPUs** (Qualcomm QNN requires building ONNX Runtime with the QNN EP yourself; MediaTek NeuroPilot is TFLite-only). Three paths that actually work are kept:

| Option | Implementation | Notes |
|---|---|---|
| Auto | XNNPACK → CPU | Default. Tries XNNPACK, falls back to CPU |
| CPU | `CPUExecutionProvider` | Most stable |
| **GPU accelerated** | `addNnapi(EnumSet.of(NNAPIFlags.CPU_DISABLED))` | NNAPI hardware mode: CPU fallback disabled so work goes to GPU / DSP; session creation fails and falls back to CPU when no accelerator exists |
| NNAPI | `addNnapi()` | System acceleration, CPU fallback allowed. Deprecated since Android 15 and largely driver-less on Android 16 |

- The official AAR ships **no OpenCL EP**, so ONNX Runtime has no standalone GPU backend on Android — GPU is reachable only through NNAPI hardware mode.
- The sidebar shows the SoC model. "Probe device capabilities" runs a real 14 KB `select.onnx` per backend and reports availability, timing and failure reason (copyable); "Benchmark with real model" does warmup + repeated runs on the selected model.
- Any backend that fails to create a session falls back to CPU automatically, and the log says why.

---

## 7. Known limitations

1. **Aligners: only HubertFA and TIFA.** Any other model family is neither bundled nor recognised. GAME is supported, but only for MIDI annotation (§3) — it is not an aligner, and its weights are not bundled.
2. **The AP-labelling flow (`ap_infer.py`) is not wired into the UI.**
3. **Alignment values differ from upstream by ~1e-4 s**, from thread count and float accumulation order. Expected.
4. Devices with less than 4 GB RAM may struggle with HubertFA (roughly 400 MB resident).
5. **TIFA and HubertFA can disagree on where an initial consonant belongs.** TIFA is a deterministic decoder. HubertFA exposes `pad_times` (1/3/5): it runs the audio several times, each with a different amount of silence padded at both ends (`pad_length * i / pad_times`), then merges and de-duplicates the results — the inputs really do differ, so 3 or 5 can change the alignment. The app defaults to 1 (a single pass); upstream's desktop default is 3. When the initial consonant is swallowed by the preceding `R`/`SP` span, use "Initial offset prefetch" rather than relying on more passes.
6. **Scope of oto.ini verification**: VCV alias coverage was checked over 840 alias forms, all present; initial `- X` entries are 202/202 complete once the boundary test includes `R`/`SP`/`AP`/`-`/`sil`. Parameter semantics were only verified under the OpenUtau Classic renderer (negative cutoff measured from `offset`); other renderers were not tested.

---

## 8. Changes from upstream (GPL-3.0 §5)

| Kind | Detail |
|---|---|
| Reused (adapted for mobile) | `lab_generate/`, `textgrid2json/`, `json2oto/`, `oto/`, `build_diffdataset/`, `inference/`, `presets/`, `dictionary/` |
| Added | Compose MD3 UI and theming, isolated `:infer` process with crash dialog, Kotlin ONNX Runtime layer replacing Python `onnxruntime`, model / dictionary import, weight-level fp16 conversion script, CPU / GPU / NNAPI selection, ring color picker, English localisation, open-source license page |
| Replaced | `onnxruntime` → `ort_shim` + Kotlin `OrtEngine`; `soundfile` → `soundfile_shim`; `scipy.signal.resample_poly` → `audio_io.resample` (error ≤ 6.2e-4); `librosa` dropped |
| Removed | wxPython GUI (`GUI.py`), `oto/oto_param_panel.py` |
| Fixed | Merge-time dedupe keyed by `(wav, alias)` instead of alias alone (1410 lines were collapsing to 979); mid-file silent spans breaking adjacent pairs; CVV entry parameters missing; missing initial entries (boundary test now includes `R`/`SP`/`AP`/`-`/`sil`); initial fallback inside the embedded `json2cvoto` of `json2VCV_oto` |
| Not ported | `ap_infer` UI wiring |

If upstream moves forward, the Python modules can usually be copied over directly; the only adaptation point is `runner.py`.

---

## 9. Acknowledgements

This project builds on the following open-source work. Thanks to the authors and maintainers:

- [xiaobaijunya/TextGrid2oto](https://github.com/xiaobaijunya/TextGrid2oto) — the project this is ported from; author of the label / align / json / oto logic
- [openvpi/TIFA](https://github.com/openvpi/TIFA) — Token-Imputing Forced Aligner, MIT
- [wolfgitpr/HubertFA](https://github.com/wolfgitpr/HubertFA) — HuBERT-based forced alignment, Apache-2.0
- [TencentGameMate/chinese-hubert-base](https://huggingface.co/TencentGameMate/chinese-hubert-base) — Chinese HuBERT backbone, MIT
- [stakira/OpenUtau](https://github.com/stakira/OpenUtau) — reference for `oto.ini` and resampler semantics
- [openvpi/DiffSinger](https://github.com/openvpi/DiffSinger) — dataset format reference
- [praat](https://github.com/praat/praat.github.io) — TextGrid format specification
- [Chaquopy](https://chaquo.com/chaquopy/) — Python runtime on Android
- [ONNX Runtime](https://github.com/microsoft/onnxruntime) — inference engine
- Google Material Symbols — UI icons, Apache-2.0

---

## 10. License

This project is released under the **GNU GPL-3.0**; full text in [`LICENSE`](LICENSE).
Third-party licenses and modification statements are in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).

---

## 11. Credits

- Port and Android implementation: **Temmie-114**
- Original project: **xiaobaijunya**
