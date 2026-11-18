"""Regression checks for descriptor-local addresses and absolute stochastic identity."""
from pathlib import Path
import re
import unittest

from bounded_shaders import WHOLE_KERNELS
from windowed_shaders import BLOCK, relative_accesses, windowed_source
from window_contracts import relocation_contracts


ROOT = Path(__file__).resolve().parents[1]
SHADERS = ROOT.parent / "SpektraLab-Windows/engine/src/shaders/vulkan"


class WindowedShaderTests(unittest.TestCase):
    def test_nested_indices_and_writable_lvalues(self):
        self.assertEqual(relative_accesses("a[a[i+1u]] = a[j];", "a", 2),
                         "a[uint(a[uint(i+1u) - theia_dispatch.origin2]) - theia_dispatch.origin2] = a[uint(j) - theia_dispatch.origin2];")
        self.assertEqual(relative_accesses("float a[]; aa[i];", "a", 1), "float a[]; aa[i];")

    def test_every_noncollective_kernel_has_a_window_contract(self):
        shaders = {p.name: p for p in SHADERS.glob("*.comp")}
        shaders.update({p.name: p for p in (ROOT / "app/src/main/cpp/shaders/vulkan").glob("*.comp")})
        contracts = (ROOT / "app/src/main/cpp/windows/window_plan.hpp").read_text(encoding="utf-8")
        for shader in shaders.values():
            with self.subTest(kernel=shader.stem):
                source = windowed_source(shader, (SHADERS,))
                if shader.stem in WHOLE_KERNELS:
                    self.assertIsNone(source)
                    continue
                self.assertIn(f'"{shader.stem}"', contracts)
                self.assertNotIn("uint origin[", source)
                for binding in range(16):
                    self.assertIn(f"layout(offset = {8 + binding * 4}) uint origin{binding};", source)
                self.assertNotIn("gl_GlobalInvocationID.y", source)
                self.assertIn("theia_dispatch.end) return", source)
                self.assertTrue(list(BLOCK.finditer(source)))
                # RNG still receives the absolute index. Relocating only buffer
                # indices must never repeat stochastic grain at band boundaries.
                if shader.stem.startswith("spk_grain_"):
                    self.assertIn("rng_init(i,", source)
                mask = relocation_contracts()[shader.stem]
                for match in re.finditer(r"theia_dispatch.origin(\d+)", source):
                    self.assertTrue(mask & (1 << int(match[1])))

    def test_reported_failure_kernels_keep_fixed_parameters_verbatim(self):
        for kernel, fixed in {
            "spk_matmul3": ("meta.value[0]", "matrix_data.value[0]", "matrix_data.value[8]"),
            "spk_couplers_correction": ("count_data.value[0]", "positive_data.value[0]",
                                        "shift_data.value[0]", "dmax_data.value[2]", "matrix_data.value[8]"),
        }.items():
            source = windowed_source(SHADERS / (kernel + ".comp"), (SHADERS,))
            for access in fixed:
                self.assertIn(access, source)
            self.assertIn("theia_dispatch.origin0", source)

    def test_nonpointwise_inputs_and_constants_have_distinct_contracts(self):
        contracts = relocation_contracts()
        self.assertEqual(contracts["spk_iir_df_acc"], 0x49)
        self.assertEqual(contracts["spk_sep_fir_acc"], 0x25)
        self.assertEqual(contracts["spk_take_rgb"], 0x05)
        self.assertEqual(contracts["spk_to_rgba16"], 0x05)
        self.assertEqual(contracts["spk_overscan_canvas"], 0x09)
        self.assertEqual(contracts["spk_overscan_add_mask"], 0x09)


if __name__ == "__main__":
    unittest.main()
