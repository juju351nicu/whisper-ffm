#!/bin/bash

# scripts/ 配下のスクリプトはプロジェクトルートからの相対パスを前提とするため、
# どこから呼ばれてもルートへ移動する。
cd "$(dirname "$0")/.." || exit 1

MODEL_NAME=tiny
./src/main/native/whisper/models/download-ggml-model.sh $MODEL_NAME
mv ./src/main/native/whisper/models/ggml-$MODEL_NAME.bin ./