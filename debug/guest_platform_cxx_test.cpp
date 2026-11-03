#include <cassert>
#include <cstdint>
#include <cstdio>
#include <dlfcn.h>
#include <string>

extern "C" void* __macapk_arm64_native_load_abs(const char*, const char*);
extern "C" void* __macapk_arm64_native_dlsym(void*, const char*);

int main(int argc, char** argv) {
    assert(argc == 2);
    auto load = [&](const char* name) {
        void* image = __macapk_arm64_native_load_abs((std::string(argv[1]) + "/" + name).c_str(), name);
        assert(image); return image;
    };
    const char* method = "_ZNSt3__15mutex4lockEv";
    void* provider = load("libc++.so");
    void* guest = __macapk_arm64_native_dlsym(provider, method);
    assert(guest && ((int(*)())guest)() == 123);
    void* consumer = load("libcxx_consumer.so");
    auto function = (int(*)())__macapk_arm64_native_dlsym(consumer, "macapk_linkmap_fixture");
    assert(function && function() == 43);
    auto base = (uintptr_t)function - 0x200;
    assert(*(void**)(base + 0x3c0) == guest);
    void* unrelated = load("libcxx_unrelated.so");
    auto other = (int(*)())__macapk_arm64_native_dlsym(unrelated, "macapk_linkmap_fixture");
    assert(other && other() == 44);
    assert(*(void**)((uintptr_t)other - 0x200 + 0x3c0) == dlsym(RTLD_DEFAULT, method));
    std::puts("platform C++ binding: declared guest provider selected; unrelated host binding preserved");
}
