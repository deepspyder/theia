"""Negative build-gate tests, including execution with assertions disabled."""
from pathlib import Path
import struct
import subprocess
import sys
import tempfile
import unittest

from verify_windowed_spirv import verify
from window_contracts import relocation_contracts


ROOT = Path(__file__).resolve().parents[1]
FIXTURE = ROOT / "app/src/main/assets/engine/resources/vulkan/spk_matmul3.windowed-v3.spv"


class WindowAbiGateTests(unittest.TestCase):
    def test_optimized_cli_rejects_an_empty_kernel_set(self):
        with tempfile.TemporaryDirectory() as directory:
            result = subprocess.run(
                [sys.executable, "-O", str(ROOT / "tools/verify_windowed_spirv.py"), directory],
                capture_output=True, text=True,
            )
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Incomplete compiled kernel set", result.stderr)

    def test_truncated_headers_fail_with_a_clear_error(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "bad.spv"
            for data in (b"", b"\x03\x02\x23\x07", bytes(19), bytes(21)):
                with self.subTest(bytes=len(data)):
                    path.write_bytes(data)
                    with self.assertRaisesRegex(ValueError, "Truncated or unaligned"):
                        verify(path, 0)

    def test_real_artifact_and_corrupted_layouts(self):
        if not FIXTURE.is_file():
            self.skipTest("Compile the engine shader assets to exercise binary mutations")
        moving = relocation_contracts()["spk_matmul3"]
        self.assertEqual(verify(FIXTURE, moving)["origin_bindings"], [0, 3])
        words = list(struct.unpack(f"<{FIXTURE.stat().st_size // 4}I", FIXTURE.read_bytes()))
        positions = []
        cursor = 5
        while cursor < len(words):
            positions.append((cursor, words[cursor] & 0xffff))
            cursor += words[cursor] >> 16
        pointer = next(words[at + 1] for at, op in positions if op == 59 and words[at + 3] == 9)
        push = next(words[at + 3] for at, op in positions if op == 32 and words[at + 1] == pointer)
        variable = next(words[at + 2] for at, op in positions if op == 59 and words[at + 3] == 9)
        offset = next(at for at, op in positions if op == 72 and words[at + 1] == push and words[at + 3] == 35)
        uint_type = next(at for at, op in positions if op == 21 and words[at + 2:at + 4] == [32, 0])
        access = next(at for at, op in positions if op == 65 and words[at + 3] == variable)
        mutations = [
            (offset + 4, 4, "Host push offsets differ"),
            (uint_type + 3, 1, "Non-uint scalar push field"),
            (access + 4, variable, "Nested/dynamic push access"),
            (5, 0, "Malformed SPIR-V instruction"),
        ]
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "mutated.spv"
            for position, replacement, expected in mutations:
                with self.subTest(error=expected):
                    corrupted = words.copy()
                    corrupted[position] = replacement
                    path.write_bytes(struct.pack(f"<{len(corrupted)}I", *corrupted))
                    with self.assertRaisesRegex(ValueError, expected):
                        verify(path, moving)
        with self.assertRaisesRegex(ValueError, "Fixed-origin table relocated"):
            verify(FIXTURE, 0)
        with self.assertRaisesRegex(ValueError, "Missing movable binding relocation"):
            verify(FIXTURE, moving | (1 << 1))


if __name__ == "__main__":
    unittest.main()
