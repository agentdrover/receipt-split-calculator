"""FastAPI-приложение сервиса распознавания чеков."""

import logging

from fastapi import FastAPI

from app.api import health, receipts


def _configure_logging() -> None:
    """Включает журнал приложения.

    uvicorn настраивает только свои логгеры, корневой остаётся на WARNING,
    поэтому INFO-записи распознавания без этого молча теряются.
    """
    logger = logging.getLogger("receipts")
    logger.setLevel(logging.INFO)
    if not logger.handlers:
        handler = logging.StreamHandler()
        handler.setFormatter(
            logging.Formatter("%(asctime)s %(levelname)s %(name)s %(message)s")
        )
        logger.addHandler(handler)


def create_app() -> FastAPI:
    _configure_logging()
    app = FastAPI(title="receipts-api", version="0.1.0")
    app.include_router(health.router)
    app.include_router(receipts.router)
    # Потолок тела стоит до разбора multipart: 413, а не запись на диск.
    app.add_middleware(receipts.BodyLimitMiddleware)
    return app


app = create_app()
