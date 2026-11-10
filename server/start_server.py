"""Start the service without requiring a shell-specific PYTHONPATH setting."""
from pathlib import Path
import argparse
import sys

import uvicorn

if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8000)
    args = parser.parse_args()
    sys.path.insert(0, str(Path(__file__).resolve().parent / "backend"))
    uvicorn.run("app:app", host=args.host, port=args.port, workers=1)
