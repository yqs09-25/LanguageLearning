import os
import sys
import struct
import wave
import sherpa_onnx

def main():
    print("Initializing sherpa-onnx Cantonese VITS model...")
    model_dir = "/code/vits-cantonese-hf-xiaomaiiwn"
    
    vits_config = sherpa_onnx.OfflineTtsVitsModelConfig(
        model=os.path.join(model_dir, "vits-cantonese-hf-xiaomaiiwn.onnx"),
        tokens=os.path.join(model_dir, "tokens.txt"),
        lexicon=os.path.join(model_dir, "lexicon.txt"),
    )
    
    model_config = sherpa_onnx.OfflineTtsModelConfig(
        vits=vits_config,
        num_threads=2,
        debug=False,
    )
    
    config = sherpa_onnx.OfflineTtsConfig(
        model=model_config,
        rule_fsts=os.path.join(model_dir, "rule.fst"),
    )
    
    if not config.validate():
        print("Model configuration is invalid!")
        sys.exit(1)
        
    tts = sherpa_onnx.OfflineTts(config)
    
    text = "呢碟叉雞飯好好食。"
    print(f"Generating Cantonese TTS for text: '{text}'")
    audio = tts.generate(text)
    
    output_path = "/code/scratch/test_cantonese.wav"
    os.makedirs(os.path.dirname(output_path), exist_ok=True)
    
    # Save float32 samples to 16-bit PCM WAV using native wave module
    print(f"Saving generated audio to {output_path}...")
    with wave.open(output_path, "wb") as wav_file:
        wav_file.setnchannels(1)
        wav_file.setsampwidth(2)
        wav_file.setframerate(audio.sample_rate)
        
        # Convert float samples (-1.0 to 1.0) to 16-bit PCM signed integers
        int_samples = [int(max(-32768, min(32767, s * 32767))) for s in audio.samples]
        binary_data = struct.pack(f"{len(int_samples)}h", *int_samples)
        wav_file.writeframes(binary_data)
        
    print("Success! TTS generation completed successfully.")

if __name__ == "__main__":
    main()
