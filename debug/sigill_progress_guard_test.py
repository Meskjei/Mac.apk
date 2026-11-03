#!/usr/bin/env python3
"""Exercise the actual SIGILL chain with controlled signal handlers, without sending signals."""
import pathlib
import subprocess
import tempfile

root = pathlib.Path(__file__).resolve().parents[1]
source = (root / "runtime/translator/src/bionic_shim.cpp").read_text()
start = source.index("uintptr_t pc_log = uc->uc_mcontext", source.index("// v1.0.626: CHAIN"))
end = source.index("macapk_fatal_reraise(sig);", start) + len("macapk_fatal_reraise(sig);")
chain = source[start:end]
code = r'''
#include <cassert>
#include <csignal>
#include <cstdint>
#include <cstdio>
#include <sys/ucontext.h>
static struct sigaction g_old_ill_sa{};
static int mode;
static void redirect(int, siginfo_t*, void* raw) {
    static_cast<ucontext_t*>(raw)->uc_mcontext->__ss.__pc += 4;
}
static void unchanged(int, siginfo_t*, void*) {}
static int __macapk_sigchain_deliver(int sig, siginfo_t* info, void* raw) {
    if (mode == 1) { redirect(sig, info, raw); return 1; }
    return 0;
}
struct Fatal {};
[[noreturn]] static void macapk_fatal_reraise(int) { throw Fatal{}; }
static void macapk_sigill_handler(int sig, siginfo_t* info, void* ucontext) {
    auto* uc = static_cast<ucontext_t*>(ucontext);
'''
code += chain + "\n}\n"
code += r'''
static int run_tests() {
    uint32_t instructions[] = {0xd4ade001, 0xd4ade001};
    _STRUCT_MCONTEXT64 context{};
    ucontext_t uc{}; uc.uc_mcontext = &context;
    siginfo_t info{};
    auto deliver = [&](int site) {
        context.__ss.__pc = reinterpret_cast<uintptr_t>(&instructions[site]);
        macapk_sigill_handler(SIGILL, &info, &uc);
    };
    // Repeated valid HotSpot deopts at one return site must never exhaust retries.
    mode = 1;
    for (int i = 0; i < 12; ++i) deliver(0);
    mode = 0;
    g_old_ill_sa.sa_flags = SA_SIGINFO;
    g_old_ill_sa.sa_sigaction = redirect;
    for (int i = 0; i < 12; ++i) deliver(0);
    // Genuine same-PC handlers that return without progress still fail on attempt three.
    g_old_ill_sa.sa_sigaction = unchanged;
    deliver(1); deliver(1);
    bool caught = false;
    try { deliver(1); } catch (const Fatal&) { caught = true; }
    assert(caught);
    // A new trap site starts its own retry count.
    for (int i = 0; i < 12; ++i) deliver(i % 2);
    std::puts("SIGILL chain: repeated redirected deopts survive; unchanged-PC loop is still rejected");
    return 0;
}
int main() {
    try { return run_tests(); }
    catch (const Fatal&) {
        std::fputs("valid redirected trap incorrectly exhausted retries\n", stderr);
        return 1;
    }
}
'''
with tempfile.TemporaryDirectory(prefix="macapk-sigill-test-") as tmp:
    path = pathlib.Path(tmp)
    (path / "test.cpp").write_text(code)
    subprocess.run(["clang++", "-std=c++17", str(path / "test.cpp"), "-o", str(path / "test")], check=True)
    subprocess.run([str(path / "test")], check=True)
