"""Bounded wire and generated-source contracts, without changing either desktop checkout."""
import unittest
from pathlib import Path

from bounded_shaders import bounded_source, expanded_source
from windowed_shaders import windowed_source
from window_contracts import relocation_contracts


class SceneCurveContractTest(unittest.TestCase):
    def test_only_pixel_buffers_relocate(self):
        root = Path(__file__).resolve().parents[1]
        shader = root / "app/src/main/cpp/shaders/vulkan/spk_scene_curve.comp"
        self.assertEqual(relocation_contracts()[shader.stem], (1 << 0) | (1 << 4))
        source = windowed_source(shader, ())
        self.assertIn("theia_dispatch.origin0", source)
        self.assertIn("theia_dispatch.origin4", source)
        for binding in (1, 2, 3):
            # Fixed tables retain dynamic sample indexing, with no relocated origin read.
            self.assertEqual(source.count(f"theia_dispatch.origin{binding}"), 0)
        self.assertIn("theia_dispatch.end", source)
        self.assertIsNotNone(bounded_source(expanded_source(shader)))


if __name__ == "__main__":
    unittest.main()
