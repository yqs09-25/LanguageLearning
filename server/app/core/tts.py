import os
import logging
import asyncio
import hashlib
import requests
from app.config import settings

logger = logging.getLogger("tts")

async def synthesize_cantonese_text_async(text: str, voice: str = "zh-HK-HiuMaanNeural", offline: bool = True) -> str:
    """
    Synthesize Cantonese text to speech using the native host-side local microservice.
    Accepts online premium Edge TTS (HiuMaan voice) or 100% offline macOS native 'Sinji' voice.
    Saves the returned audio directly in the local static uploads folder inside Docker.
    """
    if not text:
        return ""
        
    stable_hash = hashlib.md5(text.encode("utf-8")).hexdigest()
    
    # Check what file format the microservice will return
    ext = "m4a" if offline else "mp3"
    filename = f"tts_{stable_hash}.{ext}"
    
    audio_dir = os.path.join(settings.UPLOAD_DIR, "audio")
    os.makedirs(audio_dir, exist_ok=True)
    
    filepath = os.path.join(audio_dir, filename)
    relative_url = f"/static/audio/{filename}"
    
    # Check cache first
    if os.path.exists(filepath):
        logger.info(f"TTS Cache HIT for text '{text}': returning existing file {relative_url}")
        return relative_url
        
    try:
        logger.info(f"TTS Cache MISS for text '{text}'. Routing to host-side local microservice (offline={offline})...")
        
        # Call host native microservice
        # host.docker.internal resolves to the host machine from inside the docker container
        url = "http://host.docker.internal:9000/synthesize"
        payload = {
            "text": text,
            "voice": voice,
            "offline": offline
        }
        
        # Run synchronous HTTP request in run_in_executor to avoid blocking the async event loop
        loop = asyncio.get_running_loop()
        response = await loop.run_in_executor(
            None, 
            lambda: requests.post(url, json=payload, timeout=30)
        )
        
        if response.status_code != 200:
            raise RuntimeError(f"Host TTS service failed with status {response.status_code}: {response.text}")
            
        # Write binary content directly to the shared local volume path
        with open(filepath, "wb") as f:
            f.write(response.content)
            
        logger.info(f"Audio synthesized via host successfully and saved to {filepath}")
        return relative_url
    except Exception as e:
        logger.error(f"Failed to synthesize via host microservice: {e}")
        return ""

def synthesize_cantonese_text(text: str, voice: str = "zh-HK-HiuMaanNeural", offline: bool = True) -> str:
    """
    Synchronous wrapper to run async TTS in Celery tasks or background threads.
    """
    try:
        loop = asyncio.get_event_loop()
    except RuntimeError:
        loop = asyncio.new_event_loop()
        asyncio.set_event_loop(loop)
        
    if loop.is_running():
        # If running in async context (like FastAPI), we run it using a future
        future = asyncio.run_coroutine_threadsafe(
            synthesize_cantonese_text_async(text, voice, offline), 
            loop
        )
        return future.result()
    else:
        return loop.run_until_complete(synthesize_cantonese_text_async(text, voice, offline))


