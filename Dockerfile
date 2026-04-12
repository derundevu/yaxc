FROM debian:trixie

ENV LANG=C.UTF-8 \
    DEBIAN_FRONTEND=noninteractive

COPY build-yaxc.sh /build-yaxc.sh

ENTRYPOINT ["/build-yaxc.sh"]
