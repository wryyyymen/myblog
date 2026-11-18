"""Protocol and command safety tests that do not need an installed model."""

import json
import socket
import struct
import threading
import unittest
from xml.etree import ElementTree

from vosk_worker import (
    HERE,
    PCM_BYTES,
    handle_connection,
    load_commands,
    load_grammar,
    normalize,
    read_exact,
    recognize_frame,
    result_xml,
)


class FakeRecognizer:
    def __init__(self, model, rate, grammar, *, text, confidence):
        assert rate == 16_000
        assert "[unk]" in json.loads(grammar)
        self.text = text
        self.confidence = confidence
        self.word_details = False

    def SetWords(self, enabled):
        self.word_details = enabled

    def AcceptWaveform(self, data):
        return False

    def FinalResult(self):
        assert self.word_details
        result = {"text": self.text}
        if self.confidence is not None:
            result["result"] = [{"word": self.text, "conf": self.confidence}]
        return json.dumps(result, ensure_ascii=False)


class VoskWorkerTest(unittest.TestCase):
    def setUp(self):
        self.commands = load_commands(HERE / "commands.json")
        self.grammar = load_grammar(HERE / "grammar.json", self.commands)
        self.pcm = bytes(PCM_BYTES)

    def fake_factory(self, text, confidence):
        return lambda model, rate, grammar: FakeRecognizer(
            model, rate, grammar, text=text, confidence=confidence
        )

    def test_original_bnf_command_ids(self):
        self.assertEqual(
            self.commands,
            {
                "打开图库": 3,
                "你好": 2,
                "再见": 999,
                "打开空调": 1,
                "打开近光灯": 40,
                "打开远光灯": 41,
                "打开雾灯": 42,
                "打开双闪": 43,
                "天外来物": 44,
            },
        )

    def test_model_grammar_covers_original_phrases(self):
        self.assertEqual({normalize(phrase) for phrase in self.grammar}, set(self.commands))
        self.assertIn("打开 空调", self.grammar)
        self.assertIn("打开 远光 灯", self.grammar)

    def test_exact_phrase_with_spaces_and_punctuation_maps_to_command(self):
        result = recognize_frame(
            self.pcm, None, self.commands, self.grammar, self.fake_factory("打 开 空 调。", 0.92)
        )
        self.assertEqual(result, ("打 开 空 调。", 1, 92))

    def test_partial_and_unknown_phrases_cannot_trigger_command(self):
        for phrase in ("打开", "关闭空调", "[unk]", ""):
            with self.subTest(phrase=phrase):
                result = recognize_frame(
                    self.pcm, None, self.commands, self.grammar, self.fake_factory(phrase, 0.99)
                )
                self.assertIsNone(result[1])

    def test_low_or_missing_word_confidence_cannot_trigger_command(self):
        for score in (0.69, None):
            with self.subTest(score=score):
                self.assertIsNone(
                    recognize_frame(
                        self.pcm, None, self.commands, self.grammar, self.fake_factory("你好", score)
                    )[1]
                )

    def test_xml_matches_legacy_board_shape_and_escapes_text(self):
        xml = result_xml("你好 & 世界", 2, 91)
        root = ElementTree.fromstring(xml)
        self.assertEqual(root.tag, "nlp")
        self.assertEqual(root.findtext("rawtext"), "你好 & 世界")
        self.assertEqual(root.findtext("result/confidence"), "91")
        self.assertEqual(root.find("result/object/cmd").attrib["id"], "2")

    def test_socket_frame_and_length_prefix(self):
        server, client = socket.socketpair()
        errors = []

        def work():
            try:
                with server:
                    handle_connection(
                        server, None, self.commands, self.grammar, self.fake_factory("你好", 0.96)
                    )
            except Exception as error:
                errors.append(error)

        thread = threading.Thread(target=work)
        thread.start()
        with client:
            client.sendall(self.pcm)
            length = struct.unpack("!I", read_exact(client, 4))[0]
            payload = read_exact(client, length)
        thread.join(2)
        self.assertFalse(thread.is_alive())
        self.assertFalse(errors)
        self.assertEqual(ElementTree.fromstring(payload).find("result/object/cmd").attrib["id"], "2")


if __name__ == "__main__":
    unittest.main()
