# TextGrid2oto Mobile

把 [xiaobaijunya/TextGrid2oto](https://github.com/xiaobaijunya/TextGrid2oto)（桌面 wxPython 工具）移植到 Android：
在手机上完成 **wav → lab → 强制对齐 → TextGrid → json → oto.ini** 的整条流程。

---

## 0. 上游归属与许可

> **本项目是 [xiaobaijunya/TextGrid2oto](https://github.com/xiaobaijunya/TextGrid2oto) 的衍生作品，同样以 GPL-3.0 发布。**

| 组件 | 上游 | 许可证 | 本仓库如何处理 |
|---|---|---|---|
| 移植主体 | [xiaobaijunya/TextGrid2oto](https://github.com/xiaobaijunya/TextGrid2oto) | **GPL-3.0** | Python 解码链按移动端适配后复用，整体仍为 GPL-3.0 |
| TIFA 对齐模型 | [openvpi/TIFA](https://github.com/openvpi/TIFA)（`TIFA-1.0-ST`） | **MIT** | 由 PyTorch 权重导出为 ONNX，再做权重级 fp16，属**修改版本** |
| HubertFA 对齐模型 | [wolfgitpr/HubertFA](https://github.com/wolfgitpr/HubertFA)（`1218_hfa_model` v0.0.7） | **Apache-2.0** | 仅做权重级 fp16 转换，属**修改版本** |
| HFA 底层声学模型 | [TencentGameMate/chinese-hubert-base](https://huggingface.co/TencentGameMate/chinese-hubert-base) | **MIT** | 随 HubertFA 权重一并分发 |
| 界面图标 | Google Material Symbols | **Apache-2.0** | 以 vector drawable 形式内嵌 |
| 工程图标 | 取自上游仓库 `img/TextGrid2oto.ico` | 随上游 GPL-3.0 | 转制为 `mipmap` 各密度 + 自适应图标 |

MIT 与 Apache-2.0 均单向兼容 GPLv3，因此整体以 GPL-3.0 发布不冲突。
修改声明见文末「与原项目的差异」（GPL-3.0 §5 要求）。

---

## 1. 功能

| Tab | 对应上游流程 | 说明 |
|---|---|---|
| ① 标注 | `lab_generate` | 按 wav 文件名（拼音 / 假名 / 罗马音）生成同名 `.lab`；支持分隔符切分、自动补 `SP`、去 `R`、索引表模式 |
| ② 对齐 | `inference/onnx_infer.py`、`inference/tifa_infer.py` | HubertFA / TIFA 强制对齐，输出含 `words` + `phones` 两层的 TextGrid |
| ③ JSON | `textgrid2json/` | TextGrid → `ds_phone.json` → 按字典过滤 → 重建 `word_phone.json` |
| ④ oto | `json2oto/` | 生成 CV / CVVC / VCV / ARPAsing / C+V 五种 `oto.ini`；支持模板、编码、重复次数、偏移；可选 RMVPE 生成 `.frq` 基频率表 |
| 更多 | `build_diffdataset/` 等 | 模型管理、字典库、`ph_num`、CSV↔TextGrid、words→lab、音量检测、删 `SP`、DiffSinger 数据集、GAME MIDI 标注 |

- **侧边栏**：工作流四页、资源管理（模型 / 字典）、小工具、设置（外观与主题 / 推理后端）、关于与许可。
- **主题**：明暗三态（跟随系统 / 浅色 / 深色）、Android 12+ 动态取色、圆环取色器（外环选色相、内圈调饱和度，明暗由 Material 3 的 tone 生成，不随种子色变化）。
- **底部**：进度条（状态 + 百分比）紧接运行日志（可折叠、可清空、自动滚动），下面是导航栏。
- 日志内容来自 Python 侧 `print` 的重定向，与桌面版一致；进度从 `Processing x/y`、`[x/y]` 这类文本解析。
- **字典可自行导入**：对齐页 / JSON 页的字典选择器里有「导入字典 txt…」，工具页的「字典库」也能集中管理。导入文件复制到 `filesDir/dictionaries/`，并校验「两段式」格式（`音素<TAB>音素序列`），不合格的文件会被删掉并提示。

### oto 关键开关

| 开关 | 默认 | 作用 |
|---|---|---|
| `cutoff 按文件末尾算` | 关 | 关：按 UTAU 规范从 `offset` 起算；开：从音频末尾倒算 |
| `句首 cutoff 延伸到下一音节` | 关 | 打开后句首 `- X` 的播放区间会接到第一条 VCV 条目，消除句首 10~170 ms 空档；数值比官方公式长一截 |
| `首音前探 (ms)` | 0 | 句首条目的 `offset` 往前挪 N ms，`preutterance` 同步加等长，音头位置不变。对齐常把句首辅音判给前面的 `R`/`SP` 段，听起来只剩元音时填 20~50 |

---

## 2. 技术架构

上游依赖 `onnxruntime`、`librosa`、`soundfile`、`textgrid`、`wxPython`。
Android 上没有 onnxruntime 的 Python wheel，librosa 又拖着 numba/scipy，因此拆成两层：

```
┌──────────────────────────── Compose UI (Kotlin) ────────────────────────────┐
│  5 个 Tab / 侧边栏 / 主题 / 进度条 / 日志栏                                   │
└───────────────┬──────────────────────────────────────────┬──────────────────┘
                │ Chaquopy (Python 3.12)                    │ ONNX Runtime Android
┌───────────────▼──────────────────────────┐   ┌───────────▼──────────────────┐
│ tg2oto.runner  任务编排                  │   │ OrtEngine (Kotlin)            │
│ tg2oto.inference.*  解码 / 后处理        │──▶│ 加载 session、执行推理、缓存   │
│ tg2oto.lab_generate / textgrid2json ...  │   │ 输出 .bin + manifest          │
└──────────────────────────────────────────┘   └───────────────────────────────┘
```

- **推理**：HubertFA 的输入就是 16 kHz 波形（`waveform [1, n_samples]`），Python 侧不做 fbank；
  `AlignmentDecoder` / `NonLexicalDecoder` 是纯 numpy 的 Viterbi —— 「重计算」交给 Kotlin ORT，「解码 / 写文件」留在 Python。
- **接口替身**：`tg2oto/ort_shim.py` 提供假的 `onnxruntime` 模块（`InferenceSession` / `SessionOptions` / `NodeArg`），
  内部通过 `com.tg2oto.mobile.core.OrtEngine` 执行；张量走「二进制文件 + manifest JSON」交换，避免 JNI 大数组拷贝。
- **音频**：`tg2oto/audio_io.py` 用标准库 `wave` + numpy 实现了 `load/resample/midi_to_note`，
  重采样与 `scipy.signal.resample_poly` 对齐（44.1k / 48k / 22.05k → 16k，最大误差 ≤ 6.2e-4）。`soundfile_shim.py` 顶替 `soundfile`。
- Chaquopy 侧只装 `numpy` + `textgrid` 两个包。

### 推理进程隔离

所有 Python / ONNX 工作跑在独立进程 `:infer` 的 `InferService` 里，主进程只做 UI：

```
主进程 (com.tg2oto.mobile)                :infer 进程
┌──────────────────────────┐   Messenger  ┌──────────────────────────────┐
│ Compose UI / AppViewModel│  RUN / CANCEL │ InferService                 │
│ 日志栏 / 进度条 / 弹窗    │◀─────────────│ Python(Chaquopy) + ORT 会话  │
└──────────────────────────┘ LOG/PROG/RESULT└────────────────────────────┘
```

- 200 MB 模型与 Python 的内存峰值、native 崩溃、OOM 都被隔离在子进程，界面不会跟着崩。
- 崩溃处理：线程 / Java 异常先落盘 `filesDir/infer_crash.txt` 并上报；native 崩溃或被系统回收由主进程
  `onServiceDisconnected` 兜底。两种情况都弹窗显示错误 + 最近 240 行日志，「复制日志」一键进剪贴板。
- 任务进行中点「中止」走 `CANCEL`，Python 侧 `_StopEvent` 同时轮询 `JobControl` 与自身标志。

---

## 3. 模型

### 内置模型（首次启动释放到应用私有目录）

| 模型 | 上游 | 原始 | 内置 |
|---|---|---|---|
| HubertFA `1218_hfa_model` | `wolfgitpr/HubertFA` v0.0.7 | 396 MB (fp32) | **199 MB（权重 fp16）** |
| TIFA `TIFA-1.0-ST` | TIFA 官方权重（`openvpi/TIFA`） | 160 MB (fp32) | **78 MB（权重 fp16）** |
| RMVPE 音高模型 | `lj1995/VoiceConversionWebUI`（`rmvpe.onnx`） | 362 MB (fp32) | **181 MB（整图 fp16）** |

`assets/models/` 合计约 458 MB（对齐模型 277 MB + RMVPE 181 MB），APK 约 542 MB，安装后占用约 1 GB（首次启动会复制一份到 `filesDir`）。RMVPE 只在打开「生成 frq 基频率表」时才会加载；它是整图 fp16 转换（区别于对齐模型的权重级 fp16），在 CPU EP 上实测正常——x86 上每秒音频约 0.13 秒，440 Hz 测试音读出 439.5 Hz。

### 关于 fp16

整图 fp16 转换（`onnxconverter_common` / `onnxruntime.transformers`）在这个模型上会产出非法的 Cast 图
（重复节点名 / 类型不匹配），且 CPU EP 缺少大量 fp16 kernel。
因此采用 **权重级 fp16**：`initializer` 存 fp16，消费处插 `Cast → fp32`，计算全程仍是 fp32。

| 精度 | 体积 | 速度（TIFA 主图实测） | 精度影响 | 结论 |
|---|---|---|---|---|
| fp32 | 158 MB | 64 ms | 基准 | 直接可用，占空间 |
| **fp16（权重级）** | **78 MB** | **64 ms（不变）** | logits 相对误差 ~1e-2；端到端 TextGrid 边界最大差 0.0000 s | **推荐，内置即是** |
| int8（动态量化） | 41.8 MB | **289 ms（慢 4.5 倍）** | logits 相对误差 2.08% | 能跑但更慢，不建议 |

int8 变慢的原因：`DynamicQuantizeLinear` 的量化 / 反量化开销在 ARM CPU 上远大于它省下的矩阵计算，
而这类模型（LayerNorm + 注意力 + 大量 reshape）本就不是 int8 kernel 的受益者。

> **量化放在导入之前，而不是导入时做**：Chaquopy 里没有可用的 `onnx` 库 —— PyPI 的 `onnx` 只有 x86 / arm64-mac
> 的 wheel，Android arm64 无现成产物，源码编译需要 CMake + protobuf。App 端只做「识别 + 兼容加载」。
> 转换脚本：`python3 tools/convert_weight_fp16.py in.onnx out.onnx`。

### 导入自定义模型

入口：侧边栏 → 模型管理 → 「导入 zip」。压缩包可以是模型文件夹本身，也可以外面再套一层目录。
**导入的模型在列表里直接显示压缩包的名字**（一个 zip 里有多套模型时依次加 `_2`、`_3`），只有内置的两个显示类型名；
实际落到 `filesDir/models/imported/<zip 名>`（重名自动加后缀）。

zip 内每个模型目录按「必需文件」判定能不能用：

| 类型 | 必需文件 |
|---|---|
| HubertFA | `model.onnx` + `vocab.json`（可选 `config.json`、各语言字典 `.txt`、`VERSION` = 5） |
| TIFA | `model.onnx` + `spectrogram.onnx` + `vocabulary.json` |
| GAME | `encoder.onnx` + `segmenter.onnx` + `estimator.onnx` + `dur2bd.onnx` + `bd2dur.onnx` + `config.json` |

HubertFA 和 TIFA 是两类对齐模型。GAME **不是对齐器**：它读取已经带 `ph_seq` / `ph_dur` / `ph_num` 的 `transcriptions.csv`，把 `note_seq` / `note_dur` / `note_slur`（MIDI 音符标注）写回 CSV，用于 DiffSinger 数据集打标。它的权重**不内置**，需从 [`openvpi/GAME` 的 releases](https://github.com/openvpi/GAME/releases) 自行下载后按 zip 导入；`config.json` 是必需文件，release 包里自带。音频按 `<csv 所在目录>/wavs/<name>.wav` 查找，**不支持 flac**（先转成 16bit PCM wav）。

zip 名只是显示用的标签，不参与判定：认不出 `model.onnx` + 配套文件的目录会被跳过。

**精度自动识别**：直接扫 ONNX protobuf（只读 dtype / dims，权重用 seek 跳过，200 MB 约 0.6 秒），判定 `fp32` / `fp16` / `int8`，结果缓存在模型目录，列表直接显示。

---

## 4. 构建

```bash
# JDK 17、Android SDK（platform-35 / build-tools 35）、Gradle 8.9
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
./gradlew assembleRelease
# 产物: app/build/outputs/apk/release/app-release.apk
```

- `minSdk 26`，`targetSdk 35`，仅 `arm64-v8a`（Chaquopy 17 + Python 3.12 只支持 64 位）。
- 首次构建 Chaquopy 需联网拉取 numpy / openblas 的 Android wheel 与 Python 运行时。
- 仓库不包含 `*.onnx` 权重。若要自行出包，把上游 Release 的模型按 `app/src/main/assets/models/` 结构放回即可，
  或改用 `tools/convert_weight_fp16.py` 生成 fp16 版本。
- **不要并行跑多个 gradle 任务**，会撞上 `Storage ... is already registered`。

---

## 5. 权限

App 直接读写 `/storage/emulated/0/...` 下的录音目录（与桌面版一样按路径处理文件），
因此需要 **「所有文件访问权限」**（`MANAGE_EXTERNAL_STORAGE`）。首次进入会显示横幅，点「去授权」跳系统设置。
若只用 SAF 选目录，会尝试把 `content://` 映射回真实路径，映射失败时退化为把文件复制到应用私有目录。

---

## 6. 推理后端

安卓上没有统一的 NPU 接口，本版**不接入厂商 NPU**（高通 QNN 需自行编译带 QNN EP 的 ONNX Runtime，
联发科 NeuroPilot 只支持 TFLite），只保留三条实际可用的路径：

| 选项 | 实现 | 说明 |
|---|---|---|
| 自动 | XNNPACK → CPU | 默认。先试 XNNPACK，建不成退回 CPU |
| CPU | `CPUExecutionProvider` | 最稳 |
| **GPU 加速** | `addNnapi(EnumSet.of(NNAPIFlags.CPU_DISABLED))` | NNAPI 硬件模式：禁用 CPU 回退，强制交给 GPU / DSP；没有硬件加速器时建 session 失败并回退 CPU |
| NNAPI | `addNnapi()` | 系统加速，允许 CPU 兜底。Android 15 起 Google 已弃用，Android 16 多数机型无驱动 |

- 官方 AAR **不含 OpenCL EP**，所以 ONNX Runtime 在安卓上不存在独立的 GPU 后端，GPU 只能经由 NNAPI 硬件模式使用。
- 侧边栏「推理后端」卡片显示芯片型号；「检测设备能力」用 14 KB 的 `select.onnx` 当探针实跑一遍，
  列出每种后端的可用性、耗时和失败原因（可复制）。「真实模型基准测试」用选中模型跑 warmup + 多次取平均。
- 任一后端建 session 失败都会自动回退 CPU，日志里写明退回原因。

---

## 7. 已知限制

1. **frq 生成很慢，进度按文件数走。** RMVPE 对每个 wav 整段推理，进度条按文件个数前进、不按音频时长——目录里有一个特别长的文件时会看起来卡住。速度只在 x86 上测过，真机未验证。
2. **C+V 模式用的是毫秒偏移，不是其他模式的「中点比例」。** 对齐器只给音素边界，不告诉哪一段稳定，所以五条线用相对音素起止的固定毫秒表达；一组参数适合某个声库，换声库要重调。

3. **对齐模型只支持 HubertFA 与 TIFA**：其他类型的对齐模型既不在内置里，也不会被识别。GAME 已支持，但只用于 MIDI 标注（见 §3），它不是对齐器，权重也不内置。
4. **AP 标注流程（`ap_infer.py`）未接进 UI**。
5. **对齐数值**与原版存在 1e-4 秒级差异，来自线程数 / 浮点累加顺序，属正常范围。
6. 4 GB 以下内存的老机型跑 HubertFA 可能吃力（模型常驻约 400 MB）。
7. **TIFA / HubertFA 对「句首辅音归属」的判断可能不同**：TIFA 是确定性解码；HubertFA 有 `pad_times`（1/3/5）——它会把同一段音频按不同的首尾填充长度（`pad_length * i / pad_times`）各跑一遍，再合并去重，输入确实在变，所以 3 或 5 会改变对齐结果。App 默认 1 次（单轮），电脑端默认 3 次。句首辅音被前一段 `R`/`SP` 吞掉时，用「首音前探」补偿，而不是靠重跑。
8. **oto.ini 五字段位序仍是上游顺序，尚未按 UTAU 标准重排**：实测输出形如 `500, 333, -416, 250, 125`，overlap 出负值、cutoff 出正值，任一都足以让整条失效。这是已知未修的问题，影响所有模式。
9. **oto.ini 的验证范围**：VCV 别名覆盖实测 840 种全有；句首 `- X` 在边界判定并入 `R`/`SP`/`AP`/`-`/`sil` 后 202/202 齐全。但参数语义只在 OpenUtau Classic 渲染下核对过（负 cutoff = 距 `offset` 的长度），其他渲染器未测。

---

## 8. 与原项目的差异（GPL-3.0 §5）

| 类别 | 内容 |
|---|---|
| 复用（已适配移动端） | `lab_generate/`、`textgrid2json/`、`json2oto/`、`oto/`、`build_diffdataset/`、`inference/`、`presets/`、`dictionary/` |
| 新增 | C+V 逐音素 oto 模式（`json2phone_oto.py`）、RMVPE 生成 `.frq`（`rmvpe_infer.py` + `frq_gen.py`，纯 NumPy 前端，不引 librosa）、Compose MD3 界面与主题系统、`:infer` 独立推理进程与崩溃弹窗、Kotlin ONNX Runtime 执行层（替代 Python `onnxruntime`）、模型 / 字典导入管理、权重级 fp16 转换脚本、CPU / GPU / NNAPI 后端选择、圆环取色器、英文本地化与开源许可页 |
| 替换 | `onnxruntime` → `ort_shim` + Kotlin `OrtEngine`；`soundfile` → `soundfile_shim`；`scipy.signal.resample_poly` → 自实现 `audio_io.resample`（误差 ≤ 6.2e-4）；不使用 `librosa` |
| 移除 | wxPython 图形界面（`GUI.py`）、`oto/oto_param_panel.py` |
| 修复 | 合并阶段按 `(wav, 别名)` 双键去重（原先只按别名，1410 行被砍到 979）；句中假静音切断相邻对；CVV 入口参数缺位；句首条目缺失（边界判定并入 `R`/`SP`/`AP`/`-`/`sil`）；`json2VCV_oto` 内嵌 `json2cvoto` 补句首兜底 |
| 未移植 | `ap_infer` 的 UI 接入 |

上游如有更新，移植版的 Python 部分可以直接把对应模块覆盖过来（接口层只有 `runner.py` 一处适配）。

---

## 9. 致谢

本项目建立在以下开源工作之上，感谢作者与维护者：

- [xiaobaijunya/TextGrid2oto](https://github.com/xiaobaijunya/TextGrid2oto) —— 移植的来源，整套 label / 对齐 / json / oto 逻辑的原作者
- [openvpi/TIFA](https://github.com/openvpi/TIFA) —— Token-Imputing Forced Aligner，MIT
- [wolfgitpr/HubertFA](https://github.com/wolfgitpr/HubertFA) —— HuBERT 强制对齐，Apache-2.0
- [TencentGameMate/chinese-hubert-base](https://huggingface.co/TencentGameMate/chinese-hubert-base) —— 中文 HuBERT 底座，MIT
- [stakira/OpenUtau](https://github.com/stakira/OpenUtau) —— `oto.ini` 与重采样语义的参照实现
- [openvpi/DiffSinger](https://github.com/openvpi/DiffSinger) —— 数据集格式参照
- [praat](https://github.com/praat/praat.github.io) —— TextGrid 格式规范
- [Chaquopy](https://chaquo.com/chaquopy/) —— Android 上的 Python 运行时
- [ONNX Runtime](https://github.com/microsoft/onnxruntime) —— 推理执行
- Google Material Symbols —— 界面图标，Apache-2.0

---

## 10. 许可

本工程以 **GNU GPL-3.0** 发布，全文见 [`LICENSE`](LICENSE)。
第三方组件的许可与修改声明见 [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md)。

---

## 11. 署名

- 移植与移动端实现：**Temmie-114**
- 原项目：**xiaobaijunya**
