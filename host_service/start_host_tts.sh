#!/bin/bash
set -e

# Setup host service directory
DIR="/Users/qs/Development/LanguageLearning2/host_service"
cd "$DIR"

echo "Initializing native Python virtual environment on host Mac..."
python3 -m venv venv

echo "Activating virtual environment..."
source venv/bin/activate

echo "Installing microservice dependencies..."
pip install --upgrade pip
pip install fastapi uvicorn edge-tts

echo "Starting local Cantonese TTS microservice natively on host M4 Mac (port 9000)..."
python host_tts_service.py
