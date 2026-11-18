"""Compare the packaged Vulkan scene kernel to independent stop-domain arithmetic.

Builds a standalone NDK executable; adds no application or JNI testing endpoint.
Original and descriptor-windowed outputs must match bitwise, including a partial
last workgroup. Run against the same Release APK used by application tests.
"""
from __future__ import annotations

import argparse
import json
import math
import os
from pathlib import Path
import struct
import subprocess
import uuid
from zipfile import ZipFile


def f32(value: float) -> float:
    return struct.unpack("<f", struct.pack("<f", value))[0]


def reference(rgb: tuple[float, ...], table: list[float], samples: int, exposure: float) -> tuple[float, ...]:
    def gain(value: float, row: int) -> float:
        if not value > 0 or not math.isfinite(value):
            return 1.0
        ev = math.log2(value / f32(.184)) + exposure
        position = min(1.0, max(0.0, (ev + 8) / 16))
        coordinate = position * (samples - 1)
        low = min(int(coordinate), samples - 2)
        fraction = coordinate - low
        out = table[row * samples + low] * (1 - fraction) + table[row * samples + low + 1] * fraction
        return 2 ** (16 * (out - position))

    luma = sum(f32(weight) * max(channel, 0.0) for weight, channel in zip((.2126729, .7151522, .0721750), rgb))
    common = gain(luma, 1)
    values = tuple(channel * common for channel in rgb)
    values = tuple(channel * gain(channel, 0) for channel in values)
    return tuple(channel * gain(channel, row + 2) for row, channel in enumerate(values))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk-root", type=Path, required=True)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--serial", default="emulator-5554")
    parser.add_argument("--out", type=Path, default=Path(".tmp/scene-curve-gpu"))
    parser.add_argument("--ndk-version", default="27.0.12077973")
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[1]
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    suffix = ".exe" if os.name == "nt" else ""
    host = "windows-x86_64" if os.name == "nt" else "linux-x86_64"
    ndk = args.sdk_root / "ndk" / args.ndk_version / "toolchains/llvm/prebuilt" / host
    adb = str(args.sdk_root / "platform-tools" / ("adb" + suffix))
    engine = root.parent / "SpektraLab-Windows/engine"
    shaders = out / "shaders"
    shaders.mkdir(exist_ok=True)
    with ZipFile(args.apk) as apk:
        for name in ("libspektralab.so", "libc++_shared.so"):
            (out / name).write_bytes(apk.read("lib/x86_64/" + name))
        names = ["spk_scene_curve" + variant + ".spv" for variant in ("", ".bounded", ".windowed-v3")]
        for name in names + ["spk_math_probe.spv", "spk_split_probe.spv"]:
            (shaders / name).write_bytes(apk.read("assets/engine/resources/vulkan/" + name))
    command = [str(ndk / "bin" / ("clang++" + suffix)), "--target=x86_64-linux-android26",
               "--sysroot=" + str(ndk / "sysroot"), "-std=c++20", "-O2", "-ffp-contract=off",
               "-I" + str(root / "app/src/main/cpp"), "-I" + str(engine / "src"),
               str(root / "tools/native/scene_curve_vulkan_audit.cpp"), "-L" + str(out),
               "-lspektralab", "-lc++_shared", "-llog", "-Wl,-rpath,$ORIGIN", "-o", str(out / "scene-audit")]
    built = subprocess.run(command, capture_output=True)
    (out / "build.log").write_bytes(built.stdout + built.stderr)
    built.check_returncode()

    def device(*items: str) -> subprocess.CompletedProcess:
        result = subprocess.run([adb, "-s", args.serial, *map(str, items)], capture_output=True)
        if result.returncode:
            raise RuntimeError((result.stdout + result.stderr).decode("utf-8", "replace"))
        return result

    remote = "/data/local/tmp/theia-scene-curve-" + uuid.uuid4().hex
    previous = device("shell", "getprop", "debug.theia.export_window_bytes").stdout.decode().strip()
    device("shell", "mkdir", "-p", remote + "/shaders")
    for name in ("scene-audit", "libspektralab.so", "libc++_shared.so"):
        device("push", str(out / name), remote + "/" + name)
    for path in shaders.glob("*.spv"):
        device("push", str(path), remote + "/shaders/" + path.name)
    device("shell", "chmod", "700", remote + "/scene-audit")
    rows = []
    try:
        device("shell", "setprop", "debug.theia.export_window_bytes", "262144")
        pixels = 40003
        input_values = []
        for index in range(pixels):
            stop = -24 + 48 * (index % 4093) / 4092
            level = .184 * 2 ** stop
            triple = (level, level * .73, level * 1.17)
            if index % 17 == 0:
                triple = (-level * .1, level, level * .2)
            if index % 97 == 0:
                triple = (0.0, -0.0, 0.0)
            input_values.extend(map(f32, triple))
        samples = 129
        for kind, exposure in (("identity", 0.0), ("luma", 0.0), ("rgb", -4.0), ("rgb", 3.5),
                               ("red", 0.0), ("combined", -2.0), ("combined", 2.0), ("flat", 0.0)):
            table = []
            for row in range(5):
                for i in range(samples):
                    x = i / (samples - 1)
                    y = x
                    if kind == "luma" and row == 1 or kind == "rgb" and row == 0:
                        y = .08 + .84 * x
                    if kind == "red" and row == 2:
                        y = min(1, x + .0625)
                    if kind == "combined":
                        y = min(1, max(0, (.8 + row * .04) * x + (.03 if row % 2 else .06)))
                    if kind == "flat" and row == 0:
                        y = .5
                    table.append(f32(y))
            text = ",".join(format(v, ".9g") for v in table)
            (out / "table.txt").write_text(text, encoding="ascii")
            settings = (samples, 8, .184, .2126729, .7151522, .0721750, exposure)
            (out / "input.bin").write_bytes(struct.pack("<I7f", pixels, *settings) + struct.pack(f"<{len(input_values)}f", *input_values))
            device("push", str(out / "input.bin"), remote + "/input.bin")
            device("push", str(out / "table.txt"), remote + "/table.txt")
            outputs = []
            for windowed in (0, 1):
                result = device("shell", "LD_LIBRARY_PATH=" + remote, remote + "/scene-audit", remote + "/shaders",
                                remote + "/input.bin", remote + "/table.txt", remote + "/result.bin", str(windowed))
                (out / f"{kind}-{exposure}-{windowed}.log").write_bytes(result.stdout + result.stderr)
                device("pull", remote + "/result.bin", str(out / "result.bin"))
                outputs.append((out / "result.bin").read_bytes())
            if outputs[0] != outputs[1]:
                raise ValueError(f"Original/windowed float pixels differ: {kind}/{exposure}")
            actual = struct.unpack(f"<{len(input_values)}f", outputs[0])
            absolute = relative = 0.0
            for pixel in range(pixels):
                at = pixel * 3
                expected = reference(tuple(input_values[at:at+3]), table, samples, exposure)
                for channel, value in enumerate(expected):
                    delta = abs(actual[at + channel] - value)
                    absolute = max(absolute, delta)
                    relative = max(relative, delta / max(abs(value), 1e-7))
                    if delta > abs(value) * 8e-5 + 1e-7:
                        raise ValueError(f"CPU reference mismatch {kind}/{exposure} pixel {pixel}/{channel}: {actual[at+channel]} vs {value}")
            row = dict(kind=kind, exposure=exposure, pixels=pixels, max_absolute_error=absolute,
                       max_relative_error=relative, original_windowed_bitwise=True)
            rows.append(row)
            print(json.dumps(row), flush=True)
            (out / "results.json").write_text(json.dumps(rows, indent=2), encoding="utf-8")
    finally:
        device("shell", "setprop", "debug.theia.export_window_bytes", previous if previous.isdecimal() else "0")
        # This UUID directory is exclusively created by this audit. No user files are in it.
        device("shell", "rm", "-f", *(remote + "/" + name for name in
            ("scene-audit", "libspektralab.so", "libc++_shared.so", "input.bin", "table.txt", "result.bin")))
        device("shell", "rm", "-f", *(remote + "/shaders/" + p.name for p in shaders.glob("*.spv")))
        device("shell", "rmdir", remote + "/shaders", remote)
    print(f"PASS {len(rows)} independent cases, {len(rows) * pixels} pixels, exact original/windowed output")


if __name__ == "__main__":
    main()
