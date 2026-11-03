#include "../runtime/translator/src/guest_data_pages.h"
#include <sys/mman.h>
#include <sys/times.h>
#include <time.h>
#include <unistd.h>
#include <cassert>
#include <cerrno>
#include <cstdio>

extern "C" void* __macapk_arm64_mmap(void*, size_t, int, int, int, off_t);
extern "C" int __macapk_arm64_munmap(void*, size_t);
extern "C" int __macapk_arm64_mprotect(void*, size_t, int);
extern "C" clock_t __macapk_arm64_times(struct tms*);
extern "C" long __macapk_arm64_syscall(long, long, long, long, long, long, long);

int main() {
    assert(__macapk_arm64_times(nullptr) != (clock_t)-1);
    struct tms process_times{};
    assert(__macapk_arm64_times(&process_times) != (clock_t)-1);
    assert(process_times.tms_utime >= 0 && process_times.tms_stime >= 0);
    struct timespec delay{0, 20000000}, before{}, after{};
    assert(clock_gettime(CLOCK_MONOTONIC, &before) == 0);
    assert(__macapk_arm64_syscall(101, (long)&delay, 0, 0, 0, 0, 0) == 0);
    assert(clock_gettime(CLOCK_MONOTONIC, &after) == 0);
    assert((after.tv_sec - before.tv_sec) * 1000000000L + after.tv_nsec - before.tv_nsec >= 10000000L);
    using namespace macapk_guest_pages;
    Range rounded;
    assert(covered(0x11000, 4096, 16384, rounded));
    assert(rounded.lo == 0x10000 && rounded.hi == 0x14000);
    assert(!covered(UINTPTR_MAX - 4095, 8192, 16384, rounded));
    assert(!covered(0x10000, 0, 16384, rounded));
    Reservations ranges;
    ranges.add({0x10000, 0x20000});
    ranges.remove({0x14000, 0x18000});
    assert(ranges.contains({0x10000, 0x14000}));
    assert(ranges.contains({0x18000, 0x20000}));
    assert(!ranges.contains({0x13000, 0x15000}));
    ranges.remove({0, UINTPTR_MAX});
    assert(!ranges.contains({0x10000, 0x14000}));

    const size_t page = (size_t)sysconf(_SC_PAGESIZE);
    assert(page == 16384);
    const int rw = PROT_READ | PROT_WRITE;
    char* owned = (char*)__macapk_arm64_mmap(nullptr, page * 3, PROT_NONE, 0x22, -1, 0);
    assert(owned != MAP_FAILED);
    errno = 0;
    const int commit = __macapk_arm64_mprotect(owned + 4096, 4096, rw);
    if (enabled()) {
        assert(commit == 0);
        owned[4096] = 42;
        assert(owned[4096] == 42);
        assert(__macapk_arm64_mprotect(owned + 4096, 4096, PROT_READ | PROT_EXEC) == -1);
    } else {
        assert(commit == -1 && errno == EINVAL);
    }
    // Host, shared and file-backed allocations must never qualify.
    void* host = mmap(nullptr, page, PROT_NONE, MAP_PRIVATE | MAP_ANON, -1, 0);
    assert(host != MAP_FAILED);
    assert(__macapk_arm64_mprotect((char*)host + 4096, 4096, rw) == -1);
    assert(munmap(host, page) == 0);
    void* shared = __macapk_arm64_mmap(nullptr, page, PROT_NONE, 0x21, -1, 0);
    assert(shared != MAP_FAILED);
    assert(__macapk_arm64_mprotect((char*)shared + 4096, 4096, rw) == -1);
    assert(__macapk_arm64_munmap(shared, page) == 0);
    FILE* file = tmpfile();
    assert(file && ftruncate(fileno(file), page) == 0);
    void* backed = __macapk_arm64_mmap(nullptr, page, PROT_NONE, 0x2, fileno(file), 0);
    assert(backed != MAP_FAILED);
    assert(__macapk_arm64_mprotect((char*)backed + 4096, 4096, rw) == -1);
    assert(__macapk_arm64_munmap(backed, page) == 0);
    fclose(file);
    // A fixed replacement of our own reserve invalidates its eligibility.
    assert(__macapk_arm64_mmap(owned, page, PROT_NONE, 0x32, -1, 0) == owned);
    assert(__macapk_arm64_mprotect(owned + 4096, 4096, rw) == -1);
    assert(__macapk_arm64_munmap(owned, page * 3) == 0);
    std::printf("guest data-page checks passed (compat=%d)\n", enabled());
}
