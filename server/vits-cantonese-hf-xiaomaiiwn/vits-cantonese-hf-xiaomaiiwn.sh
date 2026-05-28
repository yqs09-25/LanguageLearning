#!/usr/bin/env bash

set -ex

echo "name: $NAME"

pip install unidecode onnx onnxruntime pyopenjtalk jamo \
  Cython scipy \
  jieba inflect \
  ko_pron pypinyin cn2an indic_transliteration eng_to_ipa num_thai \
  opencc \
  torch==1.13.0+cpu -f https://download.pytorch.org/whl/torch_stable.html

git clone https://github.com/csukuangfj/vits-cantonese
pushd vits-cantonese/monotonic_align
git checkout cantonese

python3 setup.py build

ls -lh build/
ls -lh build/lib*/
ls -lh build/lib*/*/

cp build/lib*/monotonic_align/core*.so .

sed -i.bak s/.monotonic_align.core/.core/g ./__init__.py
git diff

popd

wget -q https://huggingface.co/xiaomaiiwn/vits-cantonese/resolve/main/model/G.pth
wget -q https://huggingface.co/xiaomaiiwn/vits-cantonese/resolve/main/model/config.json

wget -q https://raw.githubusercontent.com/csukuangfj/vits_chinese/master/aishell3/words.txt

ls -lh

./vits-cantonese-hf-xiaomaiiwn.py

ls -lh

wc -l lexicon.txt
head -n100 lexicon.txt
echo "--------------------"
tail -n100 lexicon.txt

echo "--------------------"

head -n100 tokens.txt

