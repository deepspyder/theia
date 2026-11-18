"""Independent R08 file/profile audit. Requires Pillow + NumPy; reads device evidence only."""
from __future__ import annotations

import argparse
import io
import json
import struct
import zlib
from pathlib import Path

import numpy as np
from PIL import Image, ImageCms


def rgb16(path: Path):
    data = path.read_bytes()
    assert data[:4] == b"II*\0"
    root = struct.unpack_from("<I", data, 4)[0]
    count = struct.unpack_from("<H", data, root)[0]
    tags = {}
    for i in range(count):
        tag, kind, n, value = struct.unpack_from("<HHII", data, root + 2 + i * 12)
        tags[tag] = (kind, n, value)
    width, height = tags[256][2], tags[257][2]
    strip = tags[273][2]
    samples = data[strip:strip + tags[279][2]]
    if tags[259][2] == 8:
        samples = zlib.decompress(samples)
    assert len(samples) == width * height * 6
    assert tags[258][1] == 3
    bits = struct.unpack_from("<HHH", data, tags[258][2])
    assert bits == (16, 16, 16)
    icc = data[tags[34675][2]:sum(tags[34675][1:])] if 34675 in tags else None
    return np.frombuffer(samples, "<u2").reshape(height, width, 3), icc, tags


def matrix(primaries, white):
    xy = np.asarray(primaries, dtype=float).reshape(3, 2)
    columns = np.array([xy[:, 0] / xy[:, 1], np.ones(3), (1 - xy.sum(axis=1)) / xy[:, 1]])
    x, y = white
    xyz = np.array([x / y, 1, (1 - x - y) / y])
    return columns @ np.diag(np.linalg.solve(columns, xyz))


SPACES = [
    ("sRGB", (.64, .33, .30, .60, .15, .06), (.3127, .3290)),
    ("Display P3", (.68, .32, .265, .69, .15, .06), (.3127, .3290)),
    ("Adobe RGB (1998)", (.64, .33, .21, .71, .15, .06), (.3127, .3290)),
    ("ProPhoto RGB", (.7347, .2653, .1596, .8404, .0366, .0001), (.34567, .35850)),
]
BRADFORD = np.array([[.8951, .2664, -.1614], [-.7502, 1.7135, .0367], [.0389, -.0685, 1.0296]])


def white_xyz(xy):
    x, y = xy
    return np.array([x / y, 1, (1 - x - y) / y])


def adapt(from_white, to_white):
    return np.linalg.inv(BRADFORD) @ np.diag((BRADFORD @ white_xyz(to_white)) / (BRADFORD @ white_xyz(from_white))) @ BRADFORD


def decode(rgb, c):
    if c in (0, 1):
        return np.where(rgb <= .04045, rgb / 12.92, ((rgb + .055) / 1.055) ** 2.4)
    if c == 2:
        return rgb ** (563 / 256)
    return np.where(rgb <= 16 / 512, rgb / 16, rgb ** 1.8)


def encode(linear, c):
    linear = np.maximum(linear, 0)
    if c in (0, 1):
        return np.where(linear <= .0031308, 12.92 * linear, 1.055 * linear ** (1 / 2.4) - .055)
    if c == 2:
        return linear ** (256 / 563)
    return np.where(linear <= 1 / 512, linear * 16, linear ** (1 / 1.8))


def png_icc(path):
    data = path.read_bytes()
    at, profile, srgb = 8, None, False
    while at < len(data):
        size = struct.unpack_from(">I", data, at)[0]
        kind, body = data[at+4:at+8], data[at+8:at+8+size]
        assert zlib.crc32(kind+body) & 0xffffffff == struct.unpack_from(">I", data, at+8+size)[0]
        if kind == b"iCCP":
            name, rest = body.split(b"\0", 1)
            assert 1 <= len(name) <= 79 and rest[0] == 0
            profile = zlib.decompress(rest[1:])
        srgb |= kind == b"sRGB"
        at += 12 + size
    return profile, srgb


def heif_icc(path):
    data=path.read_bytes()
    def boxes(start,end):
        result=[]
        while start<end:
            size,kind=struct.unpack_from(">I4s",data,start)
            if size==1: size=struct.unpack_from(">Q",data,start+8)[0]
            elif size==0: size=end-start
            assert size>=8 and start+size<=end
            result.append((start,size,kind));start+=size
        assert start==end
        return result
    top=boxes(0,len(data));meta=next(b for b in top if b[2]==b"meta")
    children=boxes(meta[0]+12,sum(meta[:2]));pitm=next(b for b in children if b[2]==b"pitm")
    primary=struct.unpack_from(">H" if data[pitm[0]+8]==0 else ">I",data,pitm[0]+12)[0]
    iprp=next(b for b in children if b[2]==b"iprp");properties=boxes(iprp[0]+8,sum(iprp[:2]))
    ipco=next(b for b in properties if b[2]==b"ipco");entries=boxes(ipco[0]+8,sum(ipco[:2]))
    colours=[(i+1,b) for i,b in enumerate(entries) if b[2]==b"colr"]
    assert len(colours)==1
    index,colr=colours[0];assert data[colr[0]+8:colr[0]+12]==b"prof"
    icc=data[colr[0]+12:sum(colr[:2])]
    ipma=next(b for b in properties if b[2]==b"ipma");version=data[ipma[0]+8]
    flags=struct.unpack_from(">I",data,ipma[0]+8)[0]&0xffffff
    at=ipma[0]+12;n=struct.unpack_from(">I",data,at)[0];at+=4;found=False
    for _ in range(n):
        fmt=">H" if version==0 else ">I";identifier=struct.unpack_from(fmt,data,at)[0];at+=struct.calcsize(fmt)
        count=data[at];at+=1
        associated=[]
        for _ in range(count):
            fmt=">H" if flags&1 else ">B";v=struct.unpack_from(fmt,data,at)[0];at+=struct.calcsize(fmt)
            associated.append(v&(32767 if flags&1 else 127))
        if identifier==primary: found=index in associated
    assert found and at==sum(ipma[:2])
    profile=ImageCms.ImageCmsProfile(io.BytesIO(icc));assert "sRGB" in ImageCms.getProfileDescription(profile)
    return icc


def verify(directory: Path):
    results = []
    srgb = ImageCms.createProfile("sRGB")
    for c, (name, primaries, white) in enumerate(SPACES):
        profile_bytes = (directory/f"space-{c}.icc").read_bytes()
        assert struct.unpack_from(">I", profile_bytes)[0] == len(profile_bytes)
        assert profile_bytes[36:40] == b"acsp"
        profile = ImageCms.ImageCmsProfile(io.BytesIO(profile_bytes))
        assert name in ImageCms.getProfileDescription(profile)
        d50 = (.345702915, .358538597)  # exact ICC header D50 .9642,1,.8249
        expected_matrix = adapt(white, d50) @ matrix(primaries, white)
        n = struct.unpack_from(">I", profile_bytes, 128)[0]
        tags = {}
        for i in range(n):
            sig, pos, size = struct.unpack_from(">4sII", profile_bytes, 132+i*12)
            assert pos % 4 == 0 and pos + size <= len(profile_bytes)
            tags[sig] = profile_bytes[pos:pos+size]
        actual_matrix = np.array([struct.unpack_from(">iii", tags[sig], 8) for sig in (b"rXYZ", b"gXYZ", b"bXYZ")]).T/65536
        matrix_error = float(np.max(abs(actual_matrix-expected_matrix)))
        # Existing Display P3 profile is retained byte-for-byte, with rounded standard colourants.
        assert matrix_error < .00035, (name, matrix_error)
        for g in (0, 1):
            reference = rgb16(directory/f"reference-{g}.tiff")[0]
            rgb, icc, tags = rgb16(directory/f"g{g}-c{c}.tiff")
            assert icc == profile_bytes
            packed, packed_icc, _ = rgb16(directory/f"g{g}-c{c}.deflate.tiff")
            assert np.array_equal(rgb, packed) and packed_icc == icc
            conversion = np.linalg.inv(matrix(primaries,white)) @ adapt(SPACES[0][2],white) @ matrix(SPACES[0][1],SPACES[0][2])
            linear = decode(reference.astype(float)/65535,0) @ conversion.T
            expected = np.clip(np.floor(encode(linear,c)*65535+.5),0,65535).astype(np.int32)
            error = abs(rgb.astype(np.int32)-expected)
            maximum, p99 = int(error.max()), float(np.percentile(error,99))
            # Android's standard spaces use rounded platform D65/primary constants.
            # Absolute tolerance is 20/65535; record the measured maximum, never hide it.
            assert maximum <= 20, (name,g,maximum,p99)
            png_path, jpg_path = directory/f"g{g}-c{c}.png", directory/f"g{g}-c{c}.jpg"
            png = Image.open(png_path)
            image = np.asarray(png.convert("RGB")).astype(np.int32)
            quantized = (rgb.astype(np.uint32)*255+32767)//65535
            assert np.array_equal(image,quantized)
            png_profile, has_srgb = png_icc(png_path)
            jpg = Image.open(jpg_path); jpg.load()
            assert jpg.size == png.size == (257,131)
            if c == 0:
                assert png_profile is None and has_srgb
                # Android's encoder inserts its own standard sRGB profile on the legacy path.
                if jpg.info.get("icc_profile"):
                    assert "sRGB" in ImageCms.getProfileDescription(ImageCms.ImageCmsProfile(io.BytesIO(jpg.info["icc_profile"])))
            else:
                assert png_profile == icc and not has_srgb
                assert jpg.info["icc_profile"] == icc
            assert png.getexif()[271] == jpg.getexif()[271] == "TheiaAudit"
            # LittleCMS is an independent ICC consumer, not the Android conversion implementation.
            restored = np.asarray(ImageCms.profileToProfile(png, profile, srgb, outputMode="RGB"),dtype=np.int32)
            original8 = (reference.astype(np.uint32)*255+32767)//65535
            managed_error = abs(restored-original8)
            reverse=np.linalg.inv(matrix(*SPACES[0][1:])) @ adapt(white,SPACES[0][2]) @ matrix(primaries,white)
            managed_reference=np.clip(np.floor(encode(decode(image/255.0,c)@reverse.T,0)*255+.5),0,255).astype(np.int32)
            profile_error=abs(restored-managed_reference)
            assert np.percentile(profile_error,99) <= 2, (name,g,np.percentile(profile_error,99))
            results.append(dict(space=name,grade=g,full_u16_samples=int(rgb.size),max_u16_error=maximum,p99_u16_error=p99,
                                icc_matrix_error=matrix_error,lcms_p99_8bit_error=float(np.percentile(profile_error,99)),
                                eight_bit_roundtrip_p99_loss=float(np.percentile(managed_error,99))))
    return results


def verify_application(directory: Path):
    results=[]
    for row in json.loads((directory/"manifest.json").read_text()):
        if row["format"]=="HEIF": heif_icc(directory/row["file"])
        source, _, _ = rgb16(directory/row["reference"])
        src=next(i for i,s in enumerate(SPACES) if s[0]==row["from"])
        dst=next(i for i,s in enumerate(SPACES) if s[0]==row["to"])
        convert=np.linalg.inv(matrix(*SPACES[dst][1:])) @ adapt(SPACES[src][2],SPACES[dst][2]) @ matrix(*SPACES[src][1:])
        if row["format"]=="TIFF":
            actual,icc,tags=rgb16(directory/row["file"])
            assert icc and actual.shape==source.shape
        else:
            image=Image.open(directory/(row["file"]+".decoded.png" if row["format"]=="HEIF" else row["file"]));image.load()
            actual=np.asarray(image.convert("RGB"))
            icc=image.info.get("icc_profile")
            if dst!=0: assert icc
            assert actual.shape==source.shape
        if icc:
            description=ImageCms.getProfileDescription(ImageCms.ImageCmsProfile(io.BytesIO(icc)))
            assert row["to"] in description,description
        maximum,total,count=0,0,0
        for start in range(0,source.shape[0],64):
            reference=source[start:start+64]
            expected=reference.astype(float)/65535
            if src!=dst: expected=np.clip(encode(decode(expected,src)@convert.T,dst),0,1)
            bits=65535 if row["format"]=="TIFF" else 255
            expected=np.floor(expected*bits+.5).astype(np.int32)
            delta=abs(actual[start:start+64].astype(np.int32)-expected)
            maximum=max(maximum,int(delta.max()));total+=int(delta.sum());count+=delta.size
        if row["format"] in ("JPEG","HEIF"): assert total/count < 12,(row,maximum,total/count)
        else: assert maximum <= (20 if row["format"]=="TIFF" else 1),(row,maximum)
        results.append(dict(**row,width=source.shape[1],height=source.shape[0],samples=count,max_error=maximum,mean_error=total/count))
    return results


if __name__ == "__main__":
    parser=argparse.ArgumentParser(); parser.add_argument("directory",type=Path); parser.add_argument("--json",type=Path);parser.add_argument("--application",action="store_true")
    args=parser.parse_args(); result=(verify_application if args.application else verify)(args.directory)
    text=json.dumps(result,indent=2,ensure_ascii=False)
    if args.json: args.json.write_text(text,encoding="utf-8")
    print(text)
