#!/usr/bin/env python3
"""Run the actual canary rewrite pass against guard and ordinary field comparisons."""
import pathlib
import os
import subprocess
import tempfile

root = pathlib.Path(__file__).resolve().parents[1]
source = (root / "runtime/translator/src/arm64_native.cpp").read_text()
start = source.index("size_t Image::apply_stack_canary_neutralization_live(")
end = source.index("\n// ---------------------------------------------------------------------------", start)
method = source[start:end]
code = r'''
#include <algorithm>
#include <cassert>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <ctime>
#include <sys/mman.h>
#include <unordered_set>
#include <vector>
#define MACAPK_TLS_DESC_ENABLED 0
struct Relocation { uint64_t r_offset, r_info; };
struct Symbol { uint32_t st_name; };
struct Segment { uintptr_t vaddr; size_t memsz; int prot; };
struct Image {
    uint8_t* base_;
    const char* dbg_name_ = "canary-test";
    bool image_has_tlsdesc_ = false, image_has_tprel_ = false;
    std::vector<Segment> segments_;
    Relocation* pltrel_ = nullptr;
    size_t pltrel_count_ = 0;
    Symbol* symtab_ = nullptr;
    const char* strtab_ = nullptr;
    size_t apply_stack_canary_neutralization_live(size_t*);
};
'''
code += method
code += r'''
static uint32_t bl(int from, int to) { return 0x94000000u | ((to-from)&0x3ffffff); }
static uint32_t bne(int from, int to) { return 0x54000001u | (((to-from)&0x7ffff)<<5); }
static uint32_t b(int from, int to) { return 0x14000000u | ((to-from)&0x3ffffff); }
int main() {
    alignas(4096) uint32_t words[256];
    auto reset = [&] { std::fill(std::begin(words), std::end(words), 0xd503201fu); };
    reset();
    Image im; im.base_ = reinterpret_cast<uint8_t*>(words);
    im.segments_.push_back({reinterpret_cast<uintptr_t>(words), sizeof(words), PROT_EXEC});
    Relocation relocation{0, 0}; Symbol symbol{0};
    im.pltrel_ = &relocation; im.pltrel_count_ = 1;
    im.symtab_ = &symbol; im.strtab_ = "__stack_chk_fail";
    auto stub = [&] { words[200] = 0x90000010; words[201] = 0xf9400211; };
    // UC Scheduler: compare this->task_runner against a saved argument; mismatch skips a task.
    stub();
    words[10] = 0xf9401688; // ldr x8, [x20, #0x28]
    words[11] = 0xf85f83a9; // ldur x9, [x29, #-8]
    words[12] = 0xeb09011f; // cmp x8, x9
    words[13] = bne(13, 18);
    words[18] = 0xd65f03c0;
    im.apply_stack_canary_neutralization_live(nullptr);
    assert(words[10] == 0xf9401688 && "ordinary task-runner pointer was zeroed");

    // A real spilled-pointer epilogue still resolves its mismatch to __stack_chk_fail.
    reset(); stub();
    words[10] = 0xf9401688; words[11] = 0xf85f83a9;
    words[12] = 0xeb09011f; words[13] = bne(13, 20);
    words[14] = 0xd65f03c0; words[20] = bl(20, 200);
    im.apply_stack_canary_neutralization_live(nullptr);
    assert(words[10] == 0xd2800008 && "genuine guard reload was missed");

    // Flash Sale SecurityGuard +0x155b18: success branches to the return;
    // failure takes an unconditional jump back to the shared fail block.
    reset(); stub();
    words[10] = 0xf94016a8; words[11] = 0xf85f83a9;
    words[12] = 0xeb09011f; words[13] = bne(13, 30) & ~1u; // b.eq return
    words[14] = b(14, 20); words[20] = bl(20, 200); words[30] = 0xd65f03c0;
    im.apply_stack_canary_neutralization_live(nullptr);
    assert(words[10] == 0xd2800008 && "shared failure trampoline left a TLS guard read live");

    // The taken mismatch arm can also go through a shared tail-call stub.
    reset(); stub();
    words[10] = 0xf94016a8; words[11] = 0xf85f83a9;
    words[12] = 0xeb09011f; words[13] = bne(13, 20);
    words[14] = 0xd65f03c0; words[20] = b(20, 200);
    im.apply_stack_canary_neutralization_live(nullptr);
    assert(words[10] == 0xd2800008 && "tail-called guard failure was missed");

    // The earlier recurring fault +0x13a5d4 has a return-value comparison
    // between the guard read and its unscaled saved-cookie reload.
    reset(); stub();
    words[10] = 0xf9401688; words[11] = 0x7100001f; // cmp w0, #0
    words[12] = 0xf85f83a9; words[13] = 0x1a9f17e0; // ldur x9; cset w0, eq
    words[14] = 0xeb09011f; words[15] = bne(15, 20);
    words[16] = 0xd65f03c0; words[20] = bl(20, 200);
    im.apply_stack_canary_neutralization_live(nullptr);
    assert(words[10] == 0xd2800008 && "interleaved saved-cookie reload was missed");

    // A cycle, out-of-range jump, or unreachable call is not evidence of a guard.
    for (int shape = 0; shape < 3; ++shape) {
        reset(); stub();
        words[10] = 0xf9401688; words[11] = 0xf85f83a9;
        words[12] = 0xeb09011f; words[13] = bne(13, 20);
        words[14] = 0xd65f03c0;
        words[20] = shape == 0 ? b(20, 20) : shape == 1 ? b(20, 1000) : 0xd65f03c0;
        words[21] = bl(21, 200);
        im.apply_stack_canary_neutralization_live(nullptr);
        assert(words[10] == 0xf9401688 && "unproven field comparison was rewritten");
    }

    // With wide reload enabled, a previous function's MRS is not enough evidence.
    reset(); stub();
    words[2] = 0xd53bd058; // mrs x24, TPIDR_EL0
    words[3] = 0xf9401708; // ldr x8, [x24, #0x28]
    words[4] = 0xd65f03c0;
    words[6] = 0xd10143ff; // sub sp, sp, #0x50 (non-pre-indexed frame prologue)
    words[10] = 0xf9401688; words[11] = 0xf85f83a9;
    words[12] = 0xeb09011f; words[13] = bne(13, 18);
    words[18] = 0xd65f03c0;
    im.apply_stack_canary_neutralization_live(nullptr);
    assert(words[3] == 0xd2800008);
    assert(words[10] == 0xf9401688 && "heuristic backstop zeroed an ordinary field");

    // No resolved failure stub: preserve uncertain fields, while proven TPIDR loads work.
    reset(); im.pltrel_ = nullptr;
    words[2] = 0xd53bd058; words[3] = 0xf9401708; words[4] = 0xd65f03c0;
    words[10] = 0xf9401688; words[11] = 0xf85f83a9;
    words[12] = 0xeb09011f; words[13] = bne(13, 18); words[18] = 0xd65f03c0;
    im.apply_stack_canary_neutralization_live(nullptr);
    assert(words[3] == 0xd2800008 && words[10] == 0xf9401688);
    std::puts("Canary rewrite: ordinary fields preserved; genuine guard reads adapted");
}
'''
with tempfile.TemporaryDirectory(prefix="macapk-canary-test-") as tmp:
    path = pathlib.Path(tmp)
    (path / "test.cpp").write_text(code)
    subprocess.run(["clang++", "-std=c++17", str(path / "test.cpp"), "-o", str(path / "test")], check=True)
    for wide in ("0", "1"):
        env = dict(os.environ, MACAPK_CANARY_WIDE_RELOAD=wide)
        subprocess.run([str(path / "test")], env=env, check=True)
