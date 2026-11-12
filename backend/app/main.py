"""FastAPI-приложение сервиса распознавания чеков."""

from fastapi import FastAPI
from fastapi.responses import JSONResponse

from app.api import health, receipts


def create_app() -> FastAPI:
    app = FastAPI(title="receipts-api", version="0.1.0")
    app.include_router(health.router)
    app.include_router(receipts.router)

    @app.middleware("http")
    async def reject_oversized_upload(request, call_next):
        """413 до разбора multipart: иначе большое тело уже лежит на диске."""
        if request.url.path == "/api/v1/receipts/recognize" and receipts.content_length_exceeded(
            request.headers.get("content-length")
        ):
            return JSONResponse({"detail": {"error": "too_large"}}, status_code=413)
        return await call_next(request)

    return app


app = create_app()
