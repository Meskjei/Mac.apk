#include <atomic>
#include <cassert>
#include <cstddef>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <thread>

extern "C" unsigned long __macapk_arm64_getauxval(unsigned long);
extern "C" void* __macapk_arm64_native_load_abs(const char*, const char*);
extern "C" void* __macapk_arm64_native_dlsym(void*, const char*);
struct Phdr { uint32_t type, flags; uint64_t offset, address, physical, file_size, memory_size, align; };
struct Dyn { int64_t tag; uintptr_t value; };
struct Map { uintptr_t address; const char* name; const Dyn* dynamic; Map* next; Map* previous; };
struct Debug { int version; std::atomic<Map*> map; uintptr_t breakpoint; int state; uintptr_t linker_base; };

int main(int argc, char** argv) {
    assert(argc == 4);
    if (std::strcmp(argv[3], "off") == 0) {
        assert(__macapk_arm64_getauxval(3) == 0);
        assert(__macapk_arm64_getauxval(4) == 0);
        assert(__macapk_arm64_getauxval(5) == 0);
        std::puts("guest link map opt-out preserved");
        return 0;
    }
    auto* headers = (Phdr*)__macapk_arm64_getauxval(3);
    assert(headers && __macapk_arm64_getauxval(4) == sizeof(Phdr));
    size_t count = __macapk_arm64_getauxval(5);
    assert(count == 3);
    uintptr_t base = 0;
    const Dyn* dynamic = nullptr;
    for (size_t i = 0; i < count; ++i)
        if (headers[i].type == 6) base = (uintptr_t)headers - headers[i].address;
    assert(base && std::memcmp((void*)base, "\x7f" "ELF", 4) == 0);
    for (size_t i = 0; i < count; ++i)
        if (headers[i].type == 2) dynamic = (Dyn*)(base + headers[i].address);
    assert(dynamic && dynamic[0].tag == 21 && dynamic[1].tag == 0);
    auto* debug = (Debug*)dynamic[0].value;
    assert(debug->version == 1 && debug->state == 0 && debug->breakpoint);
    Map* empty = debug->map.load(std::memory_order_acquire);
    assert(empty && !empty->next && std::strstr(empty->name, "macapk-guest-loader"));
    void* first = __macapk_arm64_native_load_abs(argv[1], "libmacapk_fixture_one.so");
    assert(first);
    auto function = (int(*)())__macapk_arm64_native_dlsym(first, "macapk_linkmap_fixture");
    assert(function && function() == 42);
    Map* one = debug->map.load(std::memory_order_acquire);
    assert(one != empty && !empty->next);
    assert(one->next && !one->next->next && one->next->previous == one);
    assert(std::strstr(one->next->name, "libmacapk_fixture_one.so"));
    assert((uintptr_t)function == one->next->address + 0x200);
    assert(one->next->dynamic == (Dyn*)(one->next->address + 0x100));
    std::atomic<bool> stop{false};
    std::thread reader([&] {
        while (!stop.load()) {
            Map* head = debug->map.load(std::memory_order_acquire);
            Map* previous = nullptr; size_t seen = 0;
            for (Map* node = head; node; node = node->next) {
                assert(node->previous == previous && node->name && node->dynamic && ++seen <= 3);
                previous = node;
            }
        }
    });
    void* second = __macapk_arm64_native_load_abs(argv[2], "libmacapk_fixture_two.so");
    assert(second);
    auto other = (int(*)())__macapk_arm64_native_dlsym(second, "macapk_linkmap_fixture");
    assert(other && other() == 43);
    stop.store(true); reader.join();
    Map* two = debug->map.load(std::memory_order_acquire);
    assert(two->next->next && !two->next->next->next);
    assert(!one->next->next && !empty->next);
    assert((Phdr*)__macapk_arm64_getauxval(3) == headers);
    std::puts("guest link map: real ELF symbols, immutable snapshots and concurrent publication passed");
}
