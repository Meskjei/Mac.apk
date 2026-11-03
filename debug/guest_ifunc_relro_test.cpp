#include <cassert>
#include <cstdint>
#include <cstdio>
#include <mach/mach.h>
#include <mach/mach_vm.h>

extern "C" void* __macapk_arm64_native_load_abs(const char*, const char*);
extern "C" void* __macapk_arm64_native_dlsym(void*, const char*);

int main(int argc, char** argv) {
    assert(argc == 3);
    auto handle = __macapk_arm64_native_load_abs(argv[1], "libmacapk_fixture_ifunc.so");
    assert(handle);
    auto function = (int(*)())__macapk_arm64_native_dlsym(handle, "macapk_linkmap_fixture");
    assert(function && function() == 42);
    uintptr_t slot = reinterpret_cast<uintptr_t>(function) - 0x200 + 0x4200;
    assert(*reinterpret_cast<uintptr_t*>(slot) == reinterpret_cast<uintptr_t>(function) + 0x30);
    mach_vm_address_t address = slot;
    mach_vm_size_t length = 0;
    vm_region_basic_info_data_64_t info{};
    mach_msg_type_number_t count = VM_REGION_BASIC_INFO_COUNT_64;
    mach_port_t object = MACH_PORT_NULL;
    assert(mach_vm_region(mach_task_self(), &address, &length, VM_REGION_BASIC_INFO_64,
        reinterpret_cast<vm_region_info_t>(&info), &count, &object) == KERN_SUCCESS);
    if (object != MACH_PORT_NULL) mach_port_deallocate(mach_task_self(), object);
    assert(address <= slot && slot < address + length);
    assert((info.protection & VM_PROT_READ) && !(info.protection & (VM_PROT_WRITE | VM_PROT_EXECUTE)));
    assert(!__macapk_arm64_native_load_abs(argv[2], "libmacapk_fixture_ifunc_rx.so"));
    std::puts("IFUNC result written before RELRO sealed; real implementation returned 42, final page R-only");
}
