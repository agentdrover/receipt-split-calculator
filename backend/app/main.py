"""FastAPI-приложение сервиса распознавания чеков."""

from fastapi import FastAPI

from app.api import health, receipts


def create_app() -> FastAPI:
    app = FastAPI(title="receipts-api", version="0.1.0")
    app.include_router(health.router)
    app.include_router(receipts.router)
    return app


app = create_app()
