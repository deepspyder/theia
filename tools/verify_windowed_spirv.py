"""Reflect binary SPIR-V: validate the host push ABI and every origin access.

This supplements spirv-val (which validates Vulkan legality). It checks the
compiled artifact, not GLSL text or a handwritten mirror of the shader layout.
"""
import argparse
import json
import struct
from pathlib import Path

from window_contracts import relocation_contracts

MINIMUM_OPERANDS = {21: 3, 30: 1, 32: 3, 43: 3, 59: 3, 65: 4, 66: 4, 72: 3}


def require(condition: bool, message: str) -> None:
    """Build gates must remain active under python -O/PYTHONOPTIMIZE."""
    if not condition:
        raise ValueError(message)


def verify(path: Path, moving: int) -> dict:
    data = path.read_bytes()
    require(len(data) >= 20 and len(data) % 4 == 0, f"Truncated or unaligned SPIR-V: {path}")
    words = struct.unpack(f"<{len(data) // 4}I", data)
    if words[0] != 0x07230203 or words[1] != 0x10000:
        raise ValueError(f"Expected SPIR-V 1.0: {path}")
    instructions = []
    cursor = 5
    while cursor < len(words):
        length, op = words[cursor] >> 16, words[cursor] & 0xffff
        if not length or cursor + length > len(words):
            raise ValueError(f"Malformed SPIR-V instruction: {path}")
        operands = words[cursor + 1:cursor + length]
        minimum = MINIMUM_OPERANDS.get(op, 0)
        require(len(operands) >= minimum, f"Truncated SPIR-V operands for opcode {op}: {path}")
        instructions.append((op, operands))
        cursor += length
    types = {args[0]: (op, args[1:]) for op, args in instructions if op in {21, 30, 32}}
    constants = {args[1]: args[2] for op, args in instructions if op == 43 and len(args) == 3}
    push = [(args[1], args[0]) for op, args in instructions if op == 59 and args[2] == 9]
    if len(push) != 1:
        raise ValueError(f"Expected one PushConstant variable: {path}")
    variable, pointer = push[0]
    op, pointer_type = types.get(pointer, (None, ()))
    require(op == 32 and len(pointer_type) == 2 and pointer_type[0] == 9,
            f"Invalid PushConstant pointer type: {path}")
    structure = pointer_type[1]
    op, members = types.get(structure, (None, ()))
    require(op == 30 and len(members) == 18, f"Not scalar origin ABI: {path}")
    require(all(types.get(item) == (21, (32, 0)) for item in members), f"Non-uint scalar push field: {path}")
    for op, operands in instructions:
        if op == 72 and operands[0] == structure and operands[2] == 35:
            require(len(operands) == 4, f"Invalid Offset decoration: {path}")
    offsets = {args[1]: args[3] for op, args in instructions
               if op == 72 and args[0] == structure and args[2] == 35}
    require(offsets == {index: index * 4 for index in range(18)}, f"Host push offsets differ: {path}")
    accessed = set()
    for op, args in instructions:
        if op not in {65, 66} or args[2] != variable:
            continue
        require(len(args) == 4 and args[3] in constants, f"Nested/dynamic push access: {path}")
        member = constants[args[3]]
        require(member < 18, f"Push member outside host layout: {path}/{member}")
        if member >= 2:
            binding = member - 2
            require(bool(moving & (1 << binding)), f"Fixed-origin table relocated: {path}/{binding}")
            accessed.add(binding)
    require(sum(1 << binding for binding in accessed) == moving, f"Missing movable binding relocation: {path}")
    return {"shader": path.name, "bytes": len(data), "push_bytes": 72,
            "origin_bindings": sorted(accessed), "relocatable_mask": moving}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--json", type=Path)
    args = parser.parse_args()
    try:
        contracts = relocation_contracts()
        paths = sorted(args.directory.glob("*.windowed-v3.spv"))
        expected = set(contracts) - {"spk_reduce_max", "spk_split_probe"}
        require({p.name.removesuffix(".windowed-v3.spv") for p in paths} == expected,
                "Incomplete compiled kernel set")
        require(not list(args.directory.glob("*.windowed.spv")),
                "Obsolete array-origin modules must not be packaged")
        report = [verify(path, contracts[path.name.removesuffix(".windowed-v3.spv")]) for path in paths]
        if args.json:
            args.json.parent.mkdir(parents=True, exist_ok=True)
            args.json.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    except (OSError, ValueError) as failure:
        parser.exit(1, f"Shader ABI validation failed: {failure}\n")
    print(f"{len(report)} compiled shaders: scalar 72-byte ABI, uint32 offsets and fixed-origin bindings verified")


if __name__ == "__main__":
    main()
