# syntax=docker/dockerfile:1.7

ARG GO_VERSION=1.26.0
ARG REMNAWAVE_NODE_IMAGE=remnawave/node:3.3.2

FROM golang:${GO_VERSION}-alpine AS xray-builder
ARG TARGETARCH
ARG XRAY_COMMIT=5ca6f4b7d4dc20a881d4330e498892697627ec0c

RUN apk add --no-cache git
RUN git clone --filter=blob:none --no-checkout https://github.com/XTLS/Xray-core.git /src/xray \
    && git -C /src/xray fetch --depth 1 origin "${XRAY_COMMIT}" \
    && git -C /src/xray checkout --detach "${XRAY_COMMIT}" \
    && test "$(git -C /src/xray rev-parse HEAD)" = "${XRAY_COMMIT}"
WORKDIR /src/xray
COPY patches/aetherlinkx-xray-core-v26.7.28.patch /tmp/aetherlinkx-xray-core.patch
RUN git apply --check /tmp/aetherlinkx-xray-core.patch \
    && git apply /tmp/aetherlinkx-xray-core.patch
RUN CGO_ENABLED=0 GOOS=linux GOARCH="${TARGETARCH}" \
    go build -trimpath -buildvcs=false \
    -ldflags='-s -w -buildid=' \
    -o /out/xray ./main

FROM ${REMNAWAVE_NODE_IMAGE}
ARG SOURCE_URL="https://github.com/OWNER/aetherlink-x"
USER root
COPY --from=xray-builder /out/xray /usr/local/bin/xray
RUN chmod 0755 /usr/local/bin/xray \
    && ln -sfn /usr/local/bin/xray /usr/local/bin/rw-core

LABEL org.opencontainers.image.title="Remnawave Node with AetherLink X"
LABEL org.opencontainers.image.description="Remnawave Node with an experimental AetherLink X wire 1.1 Xray-core"
LABEL org.opencontainers.image.source="${SOURCE_URL}"
