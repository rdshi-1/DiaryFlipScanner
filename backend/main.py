import base64
import json
import os
from typing import Annotated

from fastapi import FastAPI, File, Form, Header, HTTPException, UploadFile
from openai import OpenAI

app = FastAPI(title="DiaryFlip transcription server", version="0.1.0")
client = OpenAI(api_key=os.environ.get("OPENAI_API_KEY"))
MODEL = os.environ.get("OPENAI_MODEL", "gpt-5-mini")
EXPECTED_TOKEN = os.environ.get("DIARYFLIP_TOKEN", "").strip()
MAX_IMAGE_BYTES = 20 * 1024 * 1024

PAGE_SCHEMA = {
    "type": "object",
    "properties": {
        "page_number": {"type": "integer"},
        "detected_date": {"type": "string"},
        "transcription": {"type": "string"},
        "uncertain_passages": {
            "type": "array",
            "items": {"type": "string"},
        },
    },
    "required": [
        "page_number",
        "detected_date",
        "transcription",
        "uncertain_passages",
    ],
    "additionalProperties": False,
}


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok", "model": MODEL}


@app.post("/v1/transcribe")
async def transcribe_page(
    page_number: Annotated[int, Form()],
    image: Annotated[UploadFile, File()],
    x_diaryflip_token: Annotated[str | None, Header()] = None,
) -> dict:
    if EXPECTED_TOKEN and x_diaryflip_token != EXPECTED_TOKEN:
        raise HTTPException(status_code=401, detail="Invalid DiaryFlip token")

    if image.content_type not in {"image/jpeg", "image/jpg", "image/png", "image/webp"}:
        raise HTTPException(status_code=415, detail="Unsupported image type")

    data = await image.read()
    if not data:
        raise HTTPException(status_code=400, detail="Empty image")
    if len(data) > MAX_IMAGE_BYTES:
        raise HTTPException(status_code=413, detail="Image exceeds 20 MB")

    mime = image.content_type or "image/jpeg"
    encoded = base64.b64encode(data).decode("ascii")
    data_url = f"data:{mime};base64,{encoded}"

    prompt = f"""
Transcribe handwritten diary page {page_number} faithfully.

Rules:
- Preserve paragraph breaks and approximate line order.
- Do not modernise spelling, grammar, names, dates, or punctuation.
- Include headings and marginal notes when they are readable.
- Ignore text showing through from the reverse side of the paper.
- Represent crossed-out words as ~~crossed out~~ only when still readable.
- Never guess an unreadable word. Put [unclear] in the transcription and add a short
  description of that passage to uncertain_passages.
- detected_date must be the written date if one is clearly present, otherwise an empty string.
- Return only the requested structured result.
""".strip()

    try:
        response = client.responses.create(
            model=MODEL,
            store=False,
            input=[
                {
                    "role": "user",
                    "content": [
                        {"type": "input_text", "text": prompt},
                        {"type": "input_image", "image_url": data_url, "detail": "high"},
                    ],
                }
            ],
            text={
                "format": {
                    "type": "json_schema",
                    "name": "diary_page_transcription",
                    "description": "A faithful transcription of one handwritten diary page.",
                    "strict": True,
                    "schema": PAGE_SCHEMA,
                }
            },
        )
        result = json.loads(response.output_text)
        result["page_number"] = page_number
        return result
    except json.JSONDecodeError as exc:
        raise HTTPException(status_code=502, detail="Model returned invalid JSON") from exc
    except Exception as exc:
        raise HTTPException(status_code=502, detail=f"Transcription failed: {exc}") from exc
