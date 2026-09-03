#!/usr/bin/env python3
"""Redacted diagnostics for AetherLink X subscriptions and Xray configs.

The tool deliberately never prints credentials or private/public key material.
It emits lengths and short SHA-256 fingerprints so client and server data can be
compared without copying secrets into tickets or CI logs.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import json
import os
import socket
import ssl
import struct
import sys
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable


PLACEHOLDER_MARKERS = (
    "0.0.0.0:1",
    "application is not supported",
    "app is not supported",
    "приложение не поддерживается",
    "приложение недоступно",
)


def fingerprint(value: Any) -> dict[str, Any]:
    text = "" if value is None else str(value)
    return {
        "present": bool(text),
        "length": len(text),
        "sha256_12": hashlib.sha256(text.encode("utf-8")).hexdigest()[:12] if text else "",
    }


def decode_base64_text(raw: str) -> str | None:
    compact = "".join(raw.split())
    if not compact or any(ch not in "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_+/=" for ch in compact):
        return None
    padded = compact + "=" * ((4 - len(compact) % 4) % 4)
    for altchars in (b"-_", None):
        try:
            decoded = base64.b64decode(padded, altchars=altchars, validate=True)
            text = decoded.decode("utf-8")
            if "://" in text or text.lstrip().startswith(("{", "[")):
                return text
        except (ValueError, UnicodeDecodeError):
            pass
    return None


def normalized_body(raw: str) -> tuple[str, str]:
    stripped = raw.lstrip("\ufeff\r\n ")
    if stripped.startswith(("{", "[")) or "://" in stripped:
        return stripped, "plain"
    decoded = decode_base64_text(stripped)
    return (decoded, "base64") if decoded is not None else (stripped, "unknown")


def encoded_json(value: str) -> dict[str, Any]:
    if not value:
        return {}
    decoded = decode_base64_text(value)
    candidates = (decoded, value)
    for candidate in candidates:
        if not candidate:
            continue
        try:
            parsed = json.loads(candidate)
            if isinstance(parsed, dict):
                return parsed
        except json.JSONDecodeError:
            pass
    return {}


def url_profile_summary(line: str) -> dict[str, Any] | None:
    value = line.strip()
    if "://" not in value:
        return None
    try:
        uri = urllib.parse.urlsplit(value)
    except ValueError:
        return {"scheme": value.split("://", 1)[0].lower(), "parse_error": True}
    query = urllib.parse.parse_qs(uri.query, keep_blank_values=True)
    get = lambda name, default="": query.get(name, [default])[0]
    item: dict[str, Any] = {
        "scheme": uri.scheme.lower(),
        "name": urllib.parse.unquote(uri.fragment)[:120],
        "server": uri.hostname or "",
        "port": uri.port,
        "transport": get("type", "tcp"),
        "security": get("security", "none"),
        "credential": fingerprint(uri.username),
    }
    if item["scheme"] in ("aetherlinkx", "alx"):
        alx_security = encoded_json(get("alxSecurity"))
        turbo = encoded_json(get("turbo"))
        stealth = encoded_json(get("stealth"))
        item["alx_secret"] = fingerprint(get("secret"))
        item["reality_public_key"] = fingerprint(get("pbk") or get("password"))
        item["short_id"] = fingerprint(get("sid"))
        item["turbo"] = {
            "decoded": bool(turbo),
            "enabled": turbo.get("enabled"),
            "max_datagram_age_ms": turbo.get("maxDatagramAgeMs"),
            "max_udp_payload": turbo.get("maxUdpPayload"),
        }
        item["alx_security"] = {
            "decoded": bool(alx_security),
            "pq_mode": alx_security.get("pqMode", "off"),
            "inner_aead": alx_security.get("innerAead", False),
            "xwing_public_key": fingerprint(alx_security.get("xwingPublicKey")),
            "contains_private_key": bool(alx_security.get("xwingPrivateKey")),
        }
        item["stealth"] = {
            "decoded": bool(stealth),
            "enabled": stealth.get("enabled"),
            "min_chunk_size": stealth.get("minChunkSize"),
            "max_chunk_size": stealth.get("maxChunkSize"),
        }
    return item


def iter_outbounds(value: Any) -> Iterable[dict[str, Any]]:
    if isinstance(value, dict):
        outbounds = value.get("outbounds")
        if isinstance(outbounds, list):
            yield from (item for item in outbounds if isinstance(item, dict))
        outbound = value.get("outbound")
        if isinstance(outbound, dict):
            yield outbound
        for nested in value.values():
            yield from iter_outbounds(nested)
    elif isinstance(value, list):
        for nested in value:
            yield from iter_outbounds(nested)


def json_outbound_summary(outbound: dict[str, Any]) -> dict[str, Any]:
    protocol = str(outbound.get("protocol", "")).lower()
    settings = outbound.get("settings") if isinstance(outbound.get("settings"), dict) else {}
    stream = outbound.get("streamSettings") if isinstance(outbound.get("streamSettings"), dict) else {}
    result: dict[str, Any] = {
        "protocol": protocol,
        "tag": str(outbound.get("tag", ""))[:120],
        "server": settings.get("address", ""),
        "port": settings.get("port"),
        "transport": stream.get("network", "tcp"),
        "transport_security": stream.get("security", "none"),
    }
    if protocol in ("aetherlinkx", "alx"):
        security = settings.get("security") if isinstance(settings.get("security"), dict) else {}
        reality = stream.get("realitySettings") if isinstance(stream.get("realitySettings"), dict) else {}
        result.update(
            id=fingerprint(settings.get("id")),
            alx_secret=fingerprint(settings.get("secret")),
            pq_mode=security.get("pqMode", "off"),
            inner_aead=security.get("innerAead", False),
            xwing_public_key=fingerprint(security.get("xwingPublicKey")),
            reality_server_name=reality.get("serverName", ""),
            reality_public_key=fingerprint(reality.get("publicKey") or reality.get("password")),
            reality_short_id=fingerprint(reality.get("shortId")),
        )
    return result


def body_report(raw: str) -> dict[str, Any]:
    body, encoding = normalized_body(raw)
    lower = body.lower()
    report: dict[str, Any] = {
        "encoding": encoding,
        "raw_bytes": len(raw.encode("utf-8")),
        "decoded_bytes": len(body.encode("utf-8")),
        "provider_placeholder": any(marker in lower for marker in PLACEHOLDER_MARKERS),
    }
    try:
        parsed = json.loads(body)
    except json.JSONDecodeError:
        profiles = [item for line in body.splitlines() if (item := url_profile_summary(line))]
        report["format"] = "uri-list"
        report["profile_count"] = len(profiles)
        report["protocol_counts"] = dict(Counter(item["scheme"] for item in profiles))
        report["aetherlinkx_profiles"] = [item for item in profiles if item["scheme"] in ("aetherlinkx", "alx")]
        return report

    outbounds = [json_outbound_summary(item) for item in iter_outbounds(parsed)]
    report["format"] = "json"
    report["top_level"] = sorted(parsed.keys()) if isinstance(parsed, dict) else "array"
    report["outbound_count"] = len(outbounds)
    report["protocol_counts"] = dict(Counter(item["protocol"] for item in outbounds))
    report["aetherlinkx_profiles"] = [item for item in outbounds if item["protocol"] in ("aetherlinkx", "alx")]
    return report


def fetch_subscription(args: argparse.Namespace) -> int:
    headers = {"User-Agent": args.user_agent, "Accept": "*/*"}
    if args.hwid:
        headers.update(
            {
                "x-hwid": args.hwid,
                "x-device-os": args.device_os,
                "x-ver-os": args.os_version,
                "x-device-model": args.device_model,
            }
        )
    request = urllib.request.Request(args.url, headers=headers)
    try:
        with urllib.request.urlopen(request, timeout=args.timeout) as response:
            raw = response.read().decode("utf-8", errors="replace")
            report = body_report(raw)
            report.update(
                http_status=response.status,
                content_type=response.headers.get("content-type", ""),
                profile_title=response.headers.get("profile-title", ""),
                subscription_userinfo_present=bool(response.headers.get("subscription-userinfo")),
            )
    except urllib.error.HTTPError as error:
        report = {"http_status": error.code, "error": str(error.reason)}
    except (urllib.error.URLError, TimeoutError, socket.timeout) as error:
        report = {"http_status": 0, "error": str(error)}
    print(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True))
    return 0 if report.get("http_status") == 200 and not report.get("provider_placeholder") else 2


def inspect_body(args: argparse.Namespace) -> int:
    raw = Path(args.file).read_text(encoding="utf-8", errors="replace") if args.file != "-" else sys.stdin.read()
    report = body_report(raw)
    print(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True))
    return 0 if not report.get("provider_placeholder") else 2


def inspect_config(args: argparse.Namespace) -> int:
    parsed = json.loads(Path(args.file).read_text(encoding="utf-8"))
    inbounds = []
    for item in parsed.get("inbounds", []):
        if not isinstance(item, dict):
            continue
        settings = item.get("settings") if isinstance(item.get("settings"), dict) else {}
        users = settings.get("clients") or settings.get("users") or []
        inbounds.append(
            {
                "tag": item.get("tag", ""),
                "protocol": item.get("protocol", ""),
                "port": item.get("port"),
                "user_count": len(users) if isinstance(users, list) else None,
                "user_ids": [fingerprint(user.get("id")) for user in users if isinstance(user, dict)],
                "user_secrets": [fingerprint(user.get("secret")) for user in users if isinstance(user, dict)],
            }
        )
    report = {
        "inbounds": inbounds,
        "outbounds": [json_outbound_summary(item) for item in iter_outbounds(parsed)],
    }
    print(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True))
    return 0


def tcp_probe(args: argparse.Namespace) -> int:
    attempts = []
    try:
        addresses = socket.getaddrinfo(args.host, args.port, type=socket.SOCK_STREAM)
    except socket.gaierror as error:
        print(json.dumps({"dns": "failed", "error": str(error)}, indent=2))
        return 2
    seen: set[tuple[Any, ...]] = set()
    for family, socktype, proto, _, address in addresses:
        key = (family, address)
        if key in seen:
            continue
        seen.add(key)
        try:
            with socket.socket(family, socktype, proto) as stream:
                stream.settimeout(args.timeout)
                stream.connect(address)
            attempts.append({"address": address[0], "connected": True})
        except OSError as error:
            attempts.append({"address": address[0], "connected": False, "error": str(error)})
    print(json.dumps({"host": args.host, "port": args.port, "attempts": attempts}, indent=2))
    return 0 if any(item["connected"] for item in attempts) else 2


def read_exact(stream: socket.socket, size: int) -> bytes:
    chunks = bytearray()
    while len(chunks) < size:
        chunk = stream.recv(size - len(chunks))
        if not chunk:
            raise OSError("unexpected EOF from SOCKS5 proxy")
        chunks.extend(chunk)
    return bytes(chunks)


def socks5_reply(stream: socket.socket) -> tuple[str, int]:
    version, reply, reserved, atyp = read_exact(stream, 4)
    if version != 5 or reserved != 0 or reply != 0:
        raise OSError(f"SOCKS5 reply failed: version={version} code={reply}")
    if atyp == 1:
        address = socket.inet_ntop(socket.AF_INET, read_exact(stream, 4))
    elif atyp == 4:
        address = socket.inet_ntop(socket.AF_INET6, read_exact(stream, 16))
    elif atyp == 3:
        address = read_exact(stream, read_exact(stream, 1)[0]).decode("ascii")
    else:
        raise OSError(f"invalid SOCKS5 address type {atyp}")
    return address, struct.unpack("!H", read_exact(stream, 2))[0]


def dns_query_packet(name: str, query_id: int) -> bytes:
    labels = name.rstrip(".").split(".")
    question = b"".join(bytes((len(label),)) + label.encode("idna") for label in labels) + b"\0"
    return struct.pack("!HHHHHH", query_id, 0x0100, 1, 0, 0, 0) + question + struct.pack("!HH", 1, 1)


def socks5_dns_probe(args: argparse.Namespace) -> int:
    report: dict[str, Any] = {
        "proxy": f"{args.proxy_host}:{args.proxy_port}",
        "dns_server": f"{args.dns_server}:53",
        "query": args.query,
    }
    control: socket.socket | None = None
    udp: socket.socket | None = None
    try:
        control = socket.create_connection((args.proxy_host, args.proxy_port), timeout=args.timeout)
        control.settimeout(args.timeout)
        control.sendall(b"\x05\x01\x00")
        if read_exact(control, 2) != b"\x05\x00":
            raise OSError("SOCKS5 proxy rejected no-auth negotiation")
        control.sendall(b"\x05\x03\x00\x01\x00\x00\x00\x00\x00\x00")
        relay_host, relay_port = socks5_reply(control)
        if relay_host in ("0.0.0.0", "::"):
            relay_host = args.proxy_host
        family = socket.AF_INET6 if ":" in relay_host else socket.AF_INET
        udp = socket.socket(family, socket.SOCK_DGRAM)
        udp.settimeout(args.timeout)
        query_id = int.from_bytes(os.urandom(2), "big")
        payload = dns_query_packet(args.query, query_id)
        destination = socket.inet_pton(socket.AF_INET, args.dns_server)
        packet = b"\x00\x00\x00\x01" + destination + struct.pack("!H", 53) + payload
        udp.sendto(packet, (relay_host, relay_port))
        response, _ = udp.recvfrom(65535)
        if len(response) < 10 or response[:2] != b"\x00\x00" or response[2] != 0:
            raise OSError("invalid SOCKS5 UDP response header")
        atyp = response[3]
        offset = 4 + (4 if atyp == 1 else 16 if atyp == 4 else 1 + response[4]) + 2
        dns = response[offset:]
        if len(dns) < 12:
            raise OSError("truncated DNS response")
        response_id, flags, questions, answers = struct.unpack("!HHHH", dns[:8])
        report.update(
            udp_associate=True,
            response_id_matches=response_id == query_id,
            dns_rcode=flags & 0x000F,
            questions=questions,
            answers=answers,
            response_bytes=len(dns),
        )
        ok = response_id == query_id and (flags & 0x000F) == 0 and answers > 0
    except OSError as error:
        report["error"] = str(error)
        ok = False
    finally:
        if udp is not None:
            udp.close()
        if control is not None:
            control.close()
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0 if ok else 2


def socks5_http_probe(args: argparse.Namespace) -> int:
    """Exercise SOCKS CONNECT and transfer a complete HTTP response body."""
    uri = urllib.parse.urlsplit(args.url)
    if uri.scheme not in ("http", "https") or not uri.hostname:
        raise SystemExit("socks5-http URL must use http:// or https://")
    target_port = uri.port or (443 if uri.scheme == "https" else 80)
    host = uri.hostname.encode("idna")
    if len(host) > 255:
        raise SystemExit("target hostname is too long")
    greeting = b"\x05\x01\x00"
    connect = b"\x05\x01\x00\x03" + bytes((len(host),)) + host + struct.pack("!H", target_port)
    report: dict[str, Any] = {
        "proxy": f"{args.proxy_host}:{args.proxy_port}",
        "target": f"{uri.scheme}://{uri.hostname}:{target_port}",
        "pipeline": args.pipeline,
    }
    stream: socket.socket | None = None
    try:
        stream = socket.create_connection((args.proxy_host, args.proxy_port), timeout=args.timeout)
        stream.settimeout(args.timeout)
        if args.pipeline:
            stream.sendall(greeting + connect)
        else:
            stream.sendall(greeting)
        if read_exact(stream, 2) != b"\x05\x00":
            raise OSError("SOCKS5 proxy rejected no-auth negotiation")
        if not args.pipeline:
            stream.sendall(connect)
        socks5_reply(stream)
        if uri.scheme == "https":
            stream = ssl.create_default_context().wrap_socket(stream, server_hostname=uri.hostname)
            stream.settimeout(args.timeout)
        path = urllib.parse.urlunsplit(("", "", uri.path or "/", uri.query, ""))
        stream.sendall(
            f"GET {path} HTTP/1.1\r\nHost: {uri.hostname}\r\nConnection: close\r\n\r\n".encode("ascii")
        )
        chunks = bytearray()
        while len(chunks) <= args.max_bytes:
            chunk = stream.recv(min(65536, args.max_bytes + 1 - len(chunks)))
            if not chunk:
                break
            chunks.extend(chunk)
        header, separator, body = bytes(chunks).partition(b"\r\n\r\n")
        if not separator:
            raise OSError("HTTP response header is incomplete")
        first_line = header.splitlines()[0].decode("ascii", errors="replace")
        fields = first_line.split()
        status = int(fields[1]) if len(fields) >= 2 and fields[1].isdigit() else 0
        report.update(http_status=status, body_bytes=len(body), response_complete=len(chunks) <= args.max_bytes)
        ok = 200 <= status < 400 and len(body) >= args.min_body_bytes
    except (OSError, ssl.SSLError) as error:
        report["error"] = str(error)
        ok = False
    finally:
        if stream is not None:
            stream.close()
    print(json.dumps(report, indent=2, sort_keys=True))
    return 0 if ok else 2


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(description=__doc__)
    sub = root.add_subparsers(dest="command", required=True)

    fetch = sub.add_parser("subscription", help="fetch and safely summarize a subscription")
    fetch.add_argument("url")
    fetch.add_argument("--user-agent", default="AetherLinkX/0.6.12")
    fetch.add_argument("--hwid", default="")
    fetch.add_argument("--device-os", default="Android")
    fetch.add_argument("--os-version", default="diagnostic")
    fetch.add_argument("--device-model", default="AetherLink X diagnostic")
    fetch.add_argument("--timeout", type=float, default=15.0)
    fetch.set_defaults(func=fetch_subscription)

    body = sub.add_parser("body", help="summarize an already downloaded response")
    body.add_argument("file", help="path or - for stdin")
    body.set_defaults(func=inspect_body)

    config = sub.add_parser("config", help="summarize a client/server Xray JSON")
    config.add_argument("file")
    config.set_defaults(func=inspect_config)

    tcp = sub.add_parser("tcp", help="DNS plus TCP-connect reachability probe")
    tcp.add_argument("host")
    tcp.add_argument("port", type=int)
    tcp.add_argument("--timeout", type=float, default=5.0)
    tcp.set_defaults(func=tcp_probe)

    dns = sub.add_parser("socks5-dns", help="verify a UDP DNS query through a SOCKS5 proxy")
    dns.add_argument("--proxy-host", default="127.0.0.1")
    dns.add_argument("--proxy-port", type=int, required=True)
    dns.add_argument("--dns-server", default="1.1.1.1")
    dns.add_argument("--query", default="example.com")
    dns.add_argument("--timeout", type=float, default=10.0)
    dns.set_defaults(func=socks5_dns_probe)

    http = sub.add_parser("socks5-http", help="verify a full HTTP(S) transfer through SOCKS5")
    http.add_argument("--proxy-host", default="127.0.0.1")
    http.add_argument("--proxy-port", type=int, required=True)
    http.add_argument("--url", default="https://speed.cloudflare.com/__down?bytes=1048576")
    http.add_argument("--pipeline", action="store_true", help="pipeline SOCKS greeting and CONNECT")
    http.add_argument("--min-body-bytes", type=int, default=262144)
    http.add_argument("--max-bytes", type=int, default=2097152)
    http.add_argument("--timeout", type=float, default=30.0)
    http.set_defaults(func=socks5_http_probe)
    return root


def main() -> int:
    args = parser().parse_args()
    return int(args.func(args))


if __name__ == "__main__":
    raise SystemExit(main())
