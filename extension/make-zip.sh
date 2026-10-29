#!/usr/bin/env bash
# Gera o aoi-extension.zip na raiz do repo (o site serve-o em /aoi-extension.zip e vai também para a release).
# Dentro do zip fica uma pasta aoi-extension/ com os ficheiros da extensão, pronta para o "Load unpacked" do Chrome.
# Correr da raiz do repo: bash extension/make-zip.sh

set -e

PASTA_REPO=$(pwd)
PASTA_TEMPORARIA=$(mktemp -d)
mkdir "$PASTA_TEMPORARIA/aoi-extension"
cp -r extension/manifest.json extension/config.js extension/background.js extension/overlay.js \
      extension/site-bridge.js extension/popup.html extension/popup.js extension/icons \
      "$PASTA_TEMPORARIA/aoi-extension/"

rm -f aoi-extension.zip
cd "$PASTA_TEMPORARIA"
zip -r -q "$PASTA_REPO/aoi-extension.zip" aoi-extension
cd "$PASTA_REPO"
rm -rf "$PASTA_TEMPORARIA"
echo "aoi-extension.zip criado"
