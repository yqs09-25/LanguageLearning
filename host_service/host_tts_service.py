import os
import tempfile
import subprocess
import hashlib
import logging
import random
from fastapi import FastAPI, HTTPException
from fastapi.responses import FileResponse
from pydantic import BaseModel
import edge_tts

# Configure logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("host_tts_service")

app = FastAPI(title="Host Native Cantonese TTS Service")

class TTSRequest(BaseModel):
    text: str
    voice: str = "zh-HK-HiuMaanNeural"
    offline: bool = False

def get_available_cantonese_voices():
    """
    Dynamically queries macOS native 'say' command to find installed Cantonese (zh_HK) voices.
    Returns clean base voice names like ['Aasing', 'Fung', 'Sinji', 'Wing'].
    """
    try:
        result = subprocess.run(["say", "-v", "?"], capture_output=True, text=True, check=True)
        voices = []
        for line in result.stdout.splitlines():
            if "zh_HK" in line:
                # E.g. "Aasing (Enhanced)   zh_HK    # 你好！我叫阿成。"
                left_part = line.split("zh_HK")[0].strip()
                # Remove any "(Enhanced)" or "(Premium)" suffix to get the base voice name
                base_voice = left_part.split("(")[0].strip()
                if base_voice and base_voice not in voices:
                    voices.append(base_voice)
        if voices:
            logger.info(f"Dynamically detected installed native Cantonese voices: {voices}")
            return voices
    except Exception as e:
        logger.error(f"Failed to detect native macOS voices: {e}")
    
    # Fallback to Sinji if detection fails
    return ["Sinji"]

@app.post("/synthesize")
async def synthesize(payload: TTSRequest):
    """
    Synthesizes Cantonese text to speech on the host M4 Mac natively.
    Can run 100% offline using macOS native voices (randomly selected among Aasing, Fung, Sinji, Wing)
    or online high-fidelity Edge-TTS (randomly selected among HiuMaan, WanLung, HiuGaai).
    Returns the binary audio file.
    """
    if not payload.text.strip():
        raise HTTPException(status_code=400, detail="Text cannot be empty.")
        
    stable_hash = hashlib.md5(payload.text.encode("utf-8")).hexdigest()
    temp_dir = tempfile.gettempdir()
    
    if payload.offline:
        # Get installed voices and pick one randomly to provide organic tonal variation
        available_voices = get_available_cantonese_voices()
        selected_voice = random.choice(available_voices)
        
        # Save cache based on both text and selected voice so different voice profiles don't overwrite each other
        voice_hash = hashlib.md5(f"{payload.text}_{selected_voice}".encode("utf-8")).hexdigest()
        filename = f"native_{selected_voice}_{voice_hash}.m4a"
        filepath = os.path.join(temp_dir, filename)
        
        # Check cache on the host side first
        if os.path.exists(filepath):
            logger.info(f"Host-side offline cache HIT for voice '{selected_voice}' and text '{payload.text}'")
            return FileResponse(filepath, media_type="audio/mp4", filename=filename)
            
        logger.info(f"Synthesizing offline via macOS native voice ({selected_voice}) for: '{payload.text}'")
        
        temp_caf_path = filepath.replace(".m4a", ".caf")
        try:
            # say command on macOS natively writes highly optimized CAF audio using the selected voice
            cmd = ["say", "-v", selected_voice, payload.text, "-o", temp_caf_path]
            result = subprocess.run(cmd, capture_output=True, text=True)
            if result.returncode != 0:
                raise RuntimeError(result.stderr or "Unknown say command error")
                
            # Transcode CAF to standard, highly-compatible AAC M4A using host-side ffmpeg
            ffmpeg_cmd = [
                "/opt/homebrew/bin/ffmpeg", "-y", "-i", temp_caf_path,
                "-c:a", "aac", "-b:a", "128k", filepath
            ]
            ffmpeg_result = subprocess.run(ffmpeg_cmd, capture_output=True, text=True)
            if ffmpeg_result.returncode != 0:
                raise RuntimeError(ffmpeg_result.stderr or "FFmpeg transcoding failed")
                
            # Clean up the temporary CAF file
            if os.path.exists(temp_caf_path):
                os.remove(temp_caf_path)
                
            logger.info(f"Offline voice {selected_voice} synthesized and transcoded successfully: {filepath}")
            return FileResponse(filepath, media_type="audio/mp4", filename=filename)
        except Exception as e:
            if os.path.exists(temp_caf_path):
                try:
                    os.remove(temp_caf_path)
                except Exception:
                    pass
            logger.error(f"Failed macOS native synthesis: {e}")
            raise HTTPException(status_code=500, detail=f"macOS native synthesis failed: {str(e)}")
    else:
        # Use Microsoft Edge TTS (Default premium high-fidelity cloud neural voices)
        # Randomly choose among Edge-TTS Cantonese voices if the default is requested
        online_voices = ["zh-HK-HiuMaanNeural", "zh-HK-WanLungNeural", "zh-HK-HiuGaaiNeural"]
        selected_voice = payload.voice
        if selected_voice == "zh-HK-HiuMaanNeural":
            selected_voice = random.choice(online_voices)
            
        voice_hash = hashlib.md5(f"{payload.text}_{selected_voice}".encode("utf-8")).hexdigest()
        filename = f"edge_{selected_voice}_{voice_hash}.mp3"
        filepath = os.path.join(temp_dir, filename)
        
        # Check cache on the host side first
        if os.path.exists(filepath):
            logger.info(f"Host-side online cache HIT for voice '{selected_voice}' and text '{payload.text}'")
            return FileResponse(filepath, media_type="audio/mpeg", filename=filename)
            
        logger.info(f"Synthesizing online via Edge TTS ({selected_voice}) for: '{payload.text}'")
        
        try:
            communicate = edge_tts.Communicate(payload.text, selected_voice)
            await communicate.save(filepath)
            
            logger.info(f"Edge TTS voice {selected_voice} synthesized successfully: {filepath}")
            return FileResponse(filepath, media_type="audio/mpeg", filename=filename)
        except Exception as e:
            logger.error(f"Failed Edge TTS synthesis: {e}")
            raise HTTPException(status_code=500, detail=f"Edge TTS synthesis failed: {str(e)}")

if __name__ == "__main__":
    import uvicorn
    # Listen on all interfaces on port 9000
    uvicorn.run(app, host="0.0.0.0", port=9000)
