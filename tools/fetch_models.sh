#!/usr/bin/env bash
# 拉取并量化内置模型（工程默认不带 300MB 权重，首次构建前跑一次）
set -e
cd "$(dirname "$0")/.."

mkdir -p models_raw .tmp_models
[ -f models_raw/HubertFA_model.zip ] || curl -L -o models_raw/HubertFA_model.zip \
  https://github.com/xiaobaijunya/TextGrid2oto/releases/download/v0.1.0/HubertFA_model.zip
[ -f models_raw/TIFA_model.zip ] || curl -L -o models_raw/TIFA_model.zip \
  https://github.com/xiaobaijunya/TextGrid2oto/releases/download/v0.1.1/TIFA_model.zip

rm -rf .tmp_models/* && unzip -o models_raw/HubertFA_model.zip -d .tmp_models >/dev/null
unzip -o models_raw/TIFA_model.zip -d .tmp_models >/dev/null

mkdir -p app/src/main/assets/models
rm -rf app/src/main/assets/models/HubertFA_model app/src/main/assets/models/TIFA_model
cp -r .tmp_models/HubertFA_model app/src/main/assets/models/
cp -r .tmp_models/TIFA_model app/src/main/assets/models/

echo "== 权重级 fp16 量化（体积减半，CPU EP 兼容）=="
python3 tools/convert_weight_fp16.py \
  app/src/main/assets/models/HubertFA_model/1218_hfa_model/model.onnx \
  app/src/main/assets/models/HubertFA_model/1218_hfa_model/model.onnx.fp16
mv app/src/main/assets/models/HubertFA_model/1218_hfa_model/model.onnx.fp16 \
   app/src/main/assets/models/HubertFA_model/1218_hfa_model/model.onnx
python3 tools/convert_weight_fp16.py \
  app/src/main/assets/models/TIFA_model/TIFA-1.0-ST/model.onnx \
  app/src/main/assets/models/TIFA_model/TIFA-1.0-ST/model.onnx.fp16
mv app/src/main/assets/models/TIFA_model/TIFA-1.0-ST/model.onnx.fp16 \
   app/src/main/assets/models/TIFA_model/TIFA-1.0-ST/model.onnx

rm -rf .tmp_models
echo "== 完成：assets/models 约 277MB =="
