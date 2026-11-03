"""Minimal valid AArch64 ELF fixtures; no cross compiler required."""
import pathlib
import struct
import sys

directory = pathlib.Path(sys.argv[1])
directory.mkdir(parents=True, exist_ok=True)
mutex = "_ZNSt3__15mutex4lockEv"

def write(filename, value, export="macapk_linkmap_fixture", dependency=None, imported=None, gnu=False):
    image = bytearray(1024)
    ident = b"\x7fELF\x02\x01\x01" + bytes(9)
    struct.pack_into("<16sHHIQQQIHHHHHH", image, 0, ident, 3, 183, 1, 0, 64, 0,
                     0, 64, 56, 2, 0, 0, 0)
    struct.pack_into("<IIQQQQQQ", image, 64, 1, 5, 0, 0, 0, len(image), len(image), 4096)
    strings = bytearray(b"\0" + export.encode() + b"\0")
    dynamic = [(0x6ffffef5 if gnu else 4, 0x240), (5, 0x300), (6, 0x280), (11, 24)]
    if dependency:
        dynamic.append((1, len(strings)))
        strings += dependency.encode() + b"\0"
    if imported:
        struct.pack_into("<IBBHQQ", image, 0x2b0, len(strings), 0x12, 0, 0, 0, 0)
        strings += imported.encode() + b"\0"
        dynamic += [(7, 0x380), (8, 24), (9, 24)]
        struct.pack_into("<QQq", image, 0x380, 0x3c0, (2 << 32) | 1025, 0)
    dynamic += [(10, len(strings)), (0, 0)]
    struct.pack_into("<IIQQQQQQ", image, 120, 2, 4, 0x100, 0x100, 0,
                     len(dynamic) * 16, len(dynamic) * 16, 8)
    for index, entry in enumerate(dynamic):
        struct.pack_into("<qQ", image, 0x100 + 16 * index, *entry)
    struct.pack_into("<II", image, 0x200, 0x52800000 | (value << 5), 0xD65F03C0)
    chains = [0, 2, 0] if imported else [0, 0]
    if gnu:
        assert not imported
        hash_value = 5381
        for byte in export.encode():
            hash_value = (hash_value * 33 + byte) & 0xffffffff
        struct.pack_into("<IIIIQII", image, 0x240, 1, 1, 1, 5, 0xffffffffffffffff, 1, hash_value | 1)
    else:
        struct.pack_into("<" + "I" * (3 + len(chains)), image, 0x240, 1, len(chains), 1, *chains)
    struct.pack_into("<IBBHQQ", image, 0x298, 1, 0x12, 0, 1, 0x200, 8)
    assert len(strings) < 128
    image[0x300:0x300 + len(strings)] = strings
    (directory / filename).write_bytes(image)

write("libmacapk_fixture_one.so", 42)
write("libmacapk_fixture_two.so", 43)
write("libc++.so", 123, export=mutex)
write("libcxx_consumer.so", 43, dependency="libc++.so", imported=mutex)
write("libcxx_unrelated.so", 44, imported=mutex)
write("libmacapk_fixture_gnu.so", 45, gnu=True)

# A real IFUNC relocation into a separate RELRO page. The exported function
# loads the resolved address from that slot and tail-calls the implementation.
image = bytearray(0xc000)
ident = b"\x7fELF\x02\x01\x01" + bytes(9)
struct.pack_into("<16sHHIQQQIHHHHHH", image, 0, ident, 3, 183, 1, 0, 64, 0,
                 0, 64, 56, 4, 0, 0, 0)
struct.pack_into("<IIQQQQQQ", image, 64, 1, 5, 0, 0, 0, 0x4000, 0x4000, 0x4000)
struct.pack_into("<IIQQQQQQ", image, 120, 1, 6, 0x4000, 0x4000, 0,
                 0x8000, 0x8000, 0x4000)
dynamic = [(4, 0x240), (5, 0x300), (6, 0x280), (11, 24), (10, 24),
           (7, 0x380), (8, 24), (9, 24), (0, 0)]
struct.pack_into("<IIQQQQQQ", image, 176, 2, 6, 0x4100, 0x4100, 0,
                 len(dynamic) * 16, len(dynamic) * 16, 8)
struct.pack_into("<IIQQQQQQ", image, 232, 0x6474e552, 4, 0x4000, 0x4000, 0,
                 0x4000, 0x4000, 1)
for index, entry in enumerate(dynamic):
    struct.pack_into("<qQ", image, 0x4100 + 16 * index, *entry)
struct.pack_into("<IIII", image, 0x200, 0x10000080, 0xf9400000, 0xd61f0000, 0xd503201f)
# ADR x0, base+0x4200; LDR x0,[x0]; BR x0.
delta = 0x4200 - 0x200
struct.pack_into("<I", image, 0x200, 0x10000000 | ((delta & 3) << 29) | ((delta >> 2) << 5))
# Resolver returns the actual implementation at +0x230.
struct.pack_into("<II", image, 0x220, 0x10000080, 0xd65f03c0)
struct.pack_into("<II", image, 0x230, 0x52800000 | (42 << 5), 0xd65f03c0)
struct.pack_into("<IIIII", image, 0x240, 1, 2, 1, 0, 0)
struct.pack_into("<IBBHQQ", image, 0x298, 1, 0x12, 0, 1, 0x200, 12)
image[0x300:0x318] = b"\0macapk_linkmap_fixture\0"
struct.pack_into("<QQq", image, 0x380, 0x4200, 1032, 0x220)
(directory / "libmacapk_fixture_ifunc.so").write_bytes(image)
struct.pack_into("<Q", image, 0x380, 0x210)  # unsupported relocation into RX text
(directory / "libmacapk_fixture_ifunc_rx.so").write_bytes(image)
