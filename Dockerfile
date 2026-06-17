# syntax=docker/dockerfile:1
FROM python:3.11-slim

# uv from the official distroless image
COPY --from=ghcr.io/astral-sh/uv:latest /uv /uvx /bin/

# ca-certificates: AnkiWeb sync is HTTPS. No build toolchain needed —
# anki, sudachipy, protobuf all ship manylinux wheels.
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# uv: copy into the project venv, compile bytecode, no hardlink warnings in-container
ENV UV_PROJECT_ENVIRONMENT=/app/.venv \
    UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy

# Layer 1: deps only (cached unless lockfile changes). anki is in the `dev`
# group, so do NOT pass --no-dev.
COPY pyproject.toml uv.lock ./
RUN --mount=type=cache,target=/root/.cache/uv \
    uv sync --frozen --no-install-project

# Headless Chromium for the bot's image-search step (ankienrich/images.py). Placed
# right after the deps layer so the ~400 MB browser + apt libs cache across source
# edits. --with-deps installs the system libraries Chromium needs on slim. The
# container runs as root, so the browser under /root/.cache/ms-playwright is found
# at runtime. Without this the image step degrades to "no images → skip".
RUN /app/.venv/bin/playwright install --with-deps chromium

# Layer 2: project source + the project install
COPY ankienrich ./ankienrich
COPY card ./card
COPY README.md ./
RUN --mount=type=cache,target=/root/.cache/uv \
    uv sync --frozen

# Console scripts (anki-enrich, anki-enrich-bot) resolve from the venv bin —
# this is what lets bot.py's `anki-enrich` subprocess work.
ENV PATH="/app/.venv/bin:$PATH"

CMD ["anki-enrich-bot"]
