"""ClothSwap HTTP API backed by the pinned CoverSwap/CatVTON pipeline."""
import base64
from contextlib import asynccontextmanager
import io
import os
import threading
from typing import Literal

import cv2
import numpy as np
from PIL import Image, ImageOps
from fastapi import Depends, FastAPI, HTTPException
from pydantic import BaseModel, Field
from starlette.concurrency import run_in_threadpool

from auth import add_routes, initialize, require_session
from coverswap import config, models
from coverswap.pipeline import CoverSwapPipeline

inference_lock = threading.Lock()


@asynccontextmanager
async def lifespan(app: FastAPI):
    initialize()
    app.state.pipeline = CoverSwapPipeline(device=os.getenv("DEVICE") or None, low_vram=True)
    yield


app = FastAPI(title="ClothSwap API", version="2.0", lifespan=lifespan)
add_routes(app)


class Photo(BaseModel):
    photo: str = Field(max_length=16_000_000)


class Swap(Photo):
    garment: str = Field(max_length=16_000_000)
    person: int = Field(default=0, ge=0)
    category: Literal["upper_body", "lower_body", "dress"] = "upper_body"
    steps: int = Field(default=30, ge=1, le=60)
    seed: int = Field(default=42, ge=0, le=2_147_483_647)
    high_resolution: bool = False
    guidance: float = Field(default=4, ge=0, le=10)


def decode(encoded: str) -> np.ndarray:
    try:
        raw = base64.b64decode(encoded, validate=True)
        with Image.open(io.BytesIO(raw)) as image:
            if image.width * image.height > 16_000_000:
                raise ValueError("Image exceeds 16 megapixels.")
            rgb = np.asarray(ImageOps.exif_transpose(image).convert("RGB"))
        return cv2.cvtColor(rgb, cv2.COLOR_RGB2BGR)
    except (ValueError, OSError, Image.DecompressionBombError, cv2.error) as error:
        raise HTTPException(400, "Invalid image or image exceeds 16 megapixels.") from error


def check_weights(mode: str):
    missing = models.missing_models(mode)
    if missing:
        raise HTTPException(503, f"Server model files are missing ({missing[0].label}). Run download_models.py on the server.")


def detect_work(request: Photo):
    check_weights(config.MODE_BACKGROUND)
    image = decode(request.photo)
    with inference_lock:
        people = app.state.pipeline.detect_people(image)
    return {"people": [
        {"index": index, "label": person.label, "box": list(map(int, person.box))}
        for index, person in enumerate(people)
    ]}


def swap_work(request: Swap):
    check_weights(config.MODE_CLOTHES)
    image = decode(request.photo)
    garment = decode(request.garment)
    settings = config.TryOnSettings(
        category=request.category, steps=request.steps, seed=request.seed,
        guidance_scale=request.guidance,
        width=768 if request.high_resolution else 576,
        height=1024 if request.high_resolution else 768,
    )
    with inference_lock:
        pipeline = app.state.pipeline
        people = pipeline.detect_people(image)
        if request.person >= len(people):
            raise HTTPException(400, "Selected person is no longer available.")
        try:
            result = pipeline.swap_person(image, people[request.person], garment, settings)
        except ValueError as error:
            raise HTTPException(400, str(error)) from error
    ok, png = cv2.imencode(".png", result)
    if not ok:
        raise RuntimeError("PNG encoding failed.")
    return {"image": base64.b64encode(png).decode("ascii"), "mime": "image/png"}


@app.get("/health")
def health():
    return {
        "status": "ready",
        "backend": "CoverSwap CatVTON",
        "device": app.state.pipeline.device,
        "models_ready": not models.missing_models(config.MODE_CLOTHES),
    }


@app.post("/detect", dependencies=[Depends(require_session)])
async def detect(request: Photo):
    return await run_in_threadpool(detect_work, request)


@app.post("/swap", dependencies=[Depends(require_session)])
async def swap(request: Swap):
    return await run_in_threadpool(swap_work, request)
