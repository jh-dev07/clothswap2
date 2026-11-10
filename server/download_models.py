"""Download only the weights used by the clothing service."""
import os
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent / "backend"))
os.environ["COVERSWAP_ALLOW_DOWNLOAD"] = "1"
os.environ.pop("HF_HUB_OFFLINE", None)
os.environ.pop("TRANSFORMERS_OFFLINE", None)

from coverswap import config, models

if __name__ == "__main__":
    models.download_all(progress=lambda message: print(message, flush=True), mode=config.MODE_CLOTHES)
