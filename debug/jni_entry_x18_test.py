#!/usr/bin/env python3
"""Compile the actual entry builder/assembly into an isolated ABI regression."""
import pathlib
import subprocess
import tempfile

root = pathlib.Path(__file__).resolve().parents[1]
source = (root / "runtime/translator/src/bionic_shim.cpp").read_text()


def function(marker):
    start = source.index(marker)
    return source[start:source.index("\n}", start) + 2]


code = r'''
#include <cassert>
#include <cstdint>
#include <cstring>
#include <cstdlib>
#include <cstdio>
#include <thread>
#include <sys/mman.h>
#include <unistd.h>
'''
start = source.index("struct AbiStackMove {")
code += source[start:source.index(";", source.index("}", start)) + 1] + "\n"
for marker in ["__attribute__((naked)) static void macapk_remarshal_generic()",
               "void* macapk_alloc_exec(", "bool macapk_nmb_subint_as_int()",
               "void* macapk_build_aapcs_thunk("]:
    code += function(marker) + "\n"
code += r'''
__attribute__((naked)) uint64_t entry_flag(void*, void*) {
    __asm__ volatile("mrs x0, TPIDR_EL0\nlsr x0, x0, #48\nand x0, x0, #1\nret");
}
double floating_return(void*, void*) { return 4.25; }
uint64_t register_args(void* env, void* klass, uint64_t a, uint64_t b,
    uint64_t c, uint64_t d, uint64_t e, uint64_t f,
    double g, double h, double i, double j, double k, double l, double m, double n) {
    assert(env == (void*)0x1234 && klass == (void*)0x5678);
    return a+b+c+d+e+f+(uint64_t)(g+h+i+j+k+l+m+n);
}
uint64_t wide_stack(void*, void*, uint64_t a, uint64_t b, uint64_t c, uint64_t d,
    uint64_t e, uint64_t f, uint64_t g, uint64_t h) { return a+b+c+d+e+f+g+h; }
__attribute__((naked)) uint64_t linux_stack(void*, void*, uint64_t, uint64_t,
    uint64_t, uint64_t, uint64_t, uint64_t, int, int, uint64_t) {
    __asm__ volatile("ldr w9, [sp]\nldr w10, [sp, #8]\nldr x11, [sp, #16]\n"
                     "add x0, x9, x10\nadd x0, x0, x11\nret");
}
int main() {
    std::thread worker([] {
        uint64_t flags;
        __asm__ volatile("mrs %0, TPIDR_EL0" : "=r"(flags));
        flags &= ~(1ULL << 48);
        __asm__ volatile("msr TPIDR_EL0, %0" :: "r"(flags));
        assert(entry_flag(nullptr, nullptr) == 0);
        auto entry = (decltype(&entry_flag))macapk_build_aapcs_thunk("()J", (void*)entry_flag);
        assert(entry && "register-only JNI was left without an entry bridge");
        assert(entry(nullptr, nullptr) == 1 && "x18 preservation not enabled on fresh thread");
        ::usleep(1000);
        assert(entry_flag(nullptr, nullptr) == 1);
        auto floating = (decltype(&floating_return))macapk_build_aapcs_thunk("()D", (void*)floating_return);
        assert(floating && floating(nullptr, nullptr) == 4.25);
        auto regs = (decltype(&register_args))macapk_build_aapcs_thunk("(JJJJJJDDDDDDDD)J", (void*)register_args);
        assert(regs && regs((void*)0x1234,(void*)0x5678,1,2,3,4,5,6,1,2,3,4,5,6,7,8) == 57);
        auto wide = (decltype(&wide_stack))macapk_build_aapcs_thunk("(JJJJJJJJ)J", (void*)wide_stack);
        assert(wide && wide(nullptr,nullptr,1,2,3,4,5,6,7,8) == 36);
        auto packed = (decltype(&linux_stack))macapk_build_aapcs_thunk("(JJJJJJIIJ)J", (void*)linux_stack);
        assert(packed && packed(nullptr,nullptr,1,2,3,4,5,6,7,8,9) == 24);
        assert(!macapk_build_aapcs_thunk("(I", (void*)entry_flag));
    });
    worker.join();
    std::puts("JNI entry: fresh-thread x18, integer/FP registers, wide and packed stack args passed");
}
'''
with tempfile.TemporaryDirectory(prefix="macapk-jni-entry-") as temporary:
    temporary = pathlib.Path(temporary)
    cpp = temporary / "entry.cpp"
    executable = temporary / "entry-test"
    entitlements = temporary / "entitlements.plist"
    cpp.write_text(code)
    entitlements.write_text('''<?xml version="1.0"?><plist version="1.0"><dict>
<key>com.apple.security.cs.allow-unsigned-executable-memory</key><true/>
<key>com.apple.security.custom-x18-abi-toggle</key><true/>
</dict></plist>''')
    subprocess.run(["clang++", "-std=c++17", str(cpp), "-o", str(executable)], check=True)
    subprocess.run(["codesign", "--force", "--sign", "-", "--entitlements", str(entitlements), str(executable)], check=True)
    subprocess.run([str(executable)], check=True)
