"""Offline recognizer for the GEC210 voice controller.

Protocol: read exactly one 96,000-byte PCM frame per TCP connection; reply with
a four-byte big-endian XML length followed by UTF-8 XML. Bind to loopback only.
"""

from __future__ import annotations

import argparse
import json
import logging
import re
import socket
import struct
from pathlib import Path
from typing import Any, Callable
from xml.etree import ElementTree


PCM_BYTES = 96_000
SAMPLE_RATE = 16_000
MAX_XML_BYTES = 16_384
MIN_COMMAND_CONFIDENCE = 70
LOGGER = logging.getLogger("robot_vosk_worker")
HERE = Path(__file__).resolve().parent


def load_commands(path: Path) -> dict[str, int]:
    source = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(source, dict) or not source:
        raise ValueError("Command map must be a nonempty JSON object")
    commands: dict[str, int] = {}
    for phrase, command_id in source.items():
        normalized = normalize(phrase)
        if not normalized or normalized in commands or type(command_id) is not int or command_id < 1:
            raise ValueError("Command map contains an invalid or duplicate phrase or ID")
        commands[normalized] = command_id
    return commands


def load_grammar(path: Path, commands: dict[str, int]) -> list[str]:
    """Use the word boundaries supported by the selected Vosk model."""
    source = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(source, dict) or len(source) != len(commands):
        raise ValueError("Grammar must contain every mapped command")
    phrases: list[str] = []
    seen: set[str] = set()
    for phrase, tokenized in source.items():
        if not isinstance(phrase, str) or not isinstance(tokenized, str):
            raise ValueError("Grammar phrases must be strings")
        normalized = normalize(phrase)
        if normalized not in commands or normalized in seen or normalize(tokenized) != normalized:
            raise ValueError("Grammar phrase does not match command map")
        phrases.append(tokenized.strip())
        seen.add(normalized)
    return phrases


def normalize(text: str) -> str:
    """Ignore spacing/punctuation emitted around Chinese words, but not words."""
    return "".join(char.lower() for char in text if char.isalnum())


def read_exact(connection: socket.socket, count: int) -> bytes:
    chunks = bytearray()
    while len(chunks) < count:
        piece = connection.recv(min(8192, count - len(chunks)))
        if not piece:
            raise EOFError("Incomplete PCM frame")
        chunks.extend(piece)
    return bytes(chunks)


def recognize_frame(
    pcm: bytes,
    model: Any,
    commands: dict[str, int],
    grammar_phrases: list[str],
    recognizer_factory: Callable[..., Any],
) -> tuple[str, int | None, int]:
    if len(pcm) != PCM_BYTES:
        raise ValueError("Expected one 96,000-byte PCM frame")

    # [unk] lets the model reject speech outside the nine old BNF commands.
    grammar = json.dumps([*grammar_phrases, "[unk]"], ensure_ascii=False)
    recognizer = recognizer_factory(model, SAMPLE_RATE, grammar)
    recognizer.SetWords(True)
    chunks: list[dict[str, Any]] = []
    # Vosk may complete an utterance before the fixed three-second frame ends.
    for offset in range(0, len(pcm), 4_000):
        if recognizer.AcceptWaveform(pcm[offset : offset + 4_000]):
            chunks.append(json.loads(recognizer.Result()))
    chunks.append(json.loads(recognizer.FinalResult()))

    text = " ".join(str(chunk.get("text", "")).strip() for chunk in chunks).strip()
    text = re.sub(r"\s+", " ", text)
    if not text:
        return "", None, 0

    word_scores = [
        float(word["conf"])
        for chunk in chunks
        for word in chunk.get("result", [])
        if isinstance(word, dict) and isinstance(word.get("conf"), (int, float))
    ]
    # A grammar match alone is not evidence of recognition confidence.
    confidence = round(max(0.0, min(1.0, min(word_scores))) * 100) if word_scores else 0
    matched_id = commands.get(normalize(text))
    if matched_id is None or confidence < MIN_COMMAND_CONFIDENCE:
        return text, None, confidence
    return text, matched_id, confidence


def result_xml(text: str, command_id: int | None, confidence: int) -> bytes:
    root = ElementTree.Element("nlp")
    ElementTree.SubElement(root, "rawtext").text = text
    result = ElementTree.SubElement(root, "result")
    ElementTree.SubElement(result, "confidence").text = str(confidence)
    if command_id is not None:
        command_object = ElementTree.SubElement(result, "object")
        ElementTree.SubElement(command_object, "cmd", id=str(command_id)).text = text
    payload = ElementTree.tostring(root, encoding="utf-8", xml_declaration=True)
    if len(payload) > MAX_XML_BYTES:
        raise ValueError("Recognition XML exceeds protocol limit")
    return payload


def handle_connection(
    connection: socket.socket,
    model: Any,
    commands: dict[str, int],
    grammar_phrases: list[str],
    recognizer_factory: Callable[..., Any],
) -> None:
    pcm = read_exact(connection, PCM_BYTES)
    text, command_id, confidence = recognize_frame(
        pcm, model, commands, grammar_phrases, recognizer_factory
    )
    payload = result_xml(text, command_id, confidence)
    connection.sendall(struct.pack("!I", len(payload)) + payload)


def serve(
    model: Any,
    commands: dict[str, int],
    grammar_phrases: list[str],
    recognizer_factory: Callable[..., Any],
    port: int,
) -> None:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
        listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        listener.bind(("127.0.0.1", port))
        listener.listen(4)
        LOGGER.info("Offline robot recognizer ready on 127.0.0.1:%d", port)
        while True:
            connection, _ = listener.accept()
            with connection:
                connection.settimeout(10)
                try:
                    handle_connection(connection, model, commands, grammar_phrases, recognizer_factory)
                except (EOFError, socket.timeout, BrokenPipeError, ConnectionResetError) as error:
                    LOGGER.warning("Robot audio connection ended: %s", error)
                except Exception:
                    LOGGER.exception("Robot recognition failed")
                    payload = result_xml("识别失败", None, 0)
                    try:
                        connection.sendall(struct.pack("!I", len(payload)) + payload)
                    except OSError:
                        pass


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", type=Path, required=True, help="Extracted Vosk Chinese model directory")
    parser.add_argument("--commands", type=Path, default=HERE / "commands.json")
    parser.add_argument("--grammar", type=Path, default=HERE / "grammar.json")
    parser.add_argument("--port", type=int, default=54_322)
    parser.add_argument("--sample-pcm", type=Path, help="Recognize one 96,000-byte PCM file and exit")
    args = parser.parse_args()
    if not args.model.is_dir():
        parser.error("--model must point to an extracted Vosk model directory")
    if not 1 <= args.port <= 65_535:
        parser.error("--port must be between 1 and 65535")
    commands = load_commands(args.commands)
    grammar_phrases = load_grammar(args.grammar, commands)

    from vosk import KaldiRecognizer, Model, SetLogLevel

    SetLogLevel(-1)
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
    model = Model(str(args.model))
    if args.sample_pcm is not None:
        text, command_id, confidence = recognize_frame(
            args.sample_pcm.read_bytes(), model, commands, grammar_phrases, KaldiRecognizer
        )
        print(json.dumps({"text": text, "commandId": command_id, "confidence": confidence}, ensure_ascii=False))
        return
    serve(model, commands, grammar_phrases, KaldiRecognizer, args.port)


if __name__ == "__main__":
    main()
