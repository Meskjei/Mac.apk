#include <cassert>
#include <cerrno>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <cstdint>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/uio.h>
#include <sys/wait.h>
#include <unistd.h>

extern "C" int __macapk_arm64_ASharedMemory_create(const char*, size_t);
extern "C" int __macapk_arm64_ASharedMemory_setProt(int, int);
extern "C" size_t __macapk_arm64_ASharedMemory_getSize(int);
extern "C" int __macapk_arm64_fcntl(int, int, void*);
extern "C" int __macapk_arm64_ioctl(int, int, void*);
extern "C" int __macapk_arm64_stat(const char*, void*);
extern "C" int __macapk_arm64_fstat(int, void*);
extern "C" int __macapk_arm64_dup(int);
extern "C" int __macapk_arm64_dup2(int, int);
extern "C" int __macapk_arm64_close(int);
extern "C" int __macapk_arm64_ftruncate(int, off_t);
extern "C" ssize_t __macapk_arm64_write(int, const void*, size_t);
extern "C" ssize_t __macapk_arm64_writev(int, const struct iovec*, int);
extern "C" ssize_t __macapk_arm64_pwrite(int, const void*, size_t, off_t);
extern "C" void* __macapk_arm64_mmap(void*, size_t, int, int, int, off_t);
extern "C" int __macapk_arm64_munmap(void*, size_t);

void check_pin_ranges(size_t page) {
    constexpr int pin_op = 0x40087707, unpin_op = 0x40087708, status_op = 0x7709;
    struct Pin { uint32_t offset, length; } range{0, 0};
    int fd = __macapk_arm64_ASharedMemory_create("discardable-cache", page * 5);
    assert(fd >= 0);
    auto* memory = (char*)__macapk_arm64_mmap(nullptr, page * 5, PROT_READ | PROT_WRITE, 1, fd, 0);
    assert(memory != MAP_FAILED);
    for (size_t i = 0; i < page * 5; ++i) memory[i] = (char)(i % 251);
    int alias = __macapk_arm64_dup(fd);
    assert(alias >= 0);
    assert(__macapk_arm64_ioctl(fd, status_op, &range) == 1); // initially pinned
    assert(__macapk_arm64_ioctl(fd, unpin_op, &range) == 0); // len=0 means to end
    assert(__macapk_arm64_ioctl(alias, status_op, &range) == 0);
    range = {(uint32_t)page, (uint32_t)(page * 3)};
    assert(__macapk_arm64_ioctl(alias, pin_op, &range) == 0); // split into two unpinned ranges
    assert(__macapk_arm64_ioctl(fd, status_op, &range) == 1);
    range = {0, (uint32_t)page};
    assert(__macapk_arm64_ioctl(fd, status_op, &range) == 0);
    range = {(uint32_t)(page * 4), 0};
    assert(__macapk_arm64_ioctl(fd, status_op, &range) == 0);
    range = {(uint32_t)page, (uint32_t)(page * 3)};
    assert(__macapk_arm64_ioctl(fd, unpin_op, &range) == 0); // merge both sides
    range = {0, 0};
    assert(__macapk_arm64_ioctl(alias, pin_op, &range) == 0); // data was not purged
    assert(__macapk_arm64_ioctl(fd, status_op, &range) == 1);
    assert(__macapk_arm64_ioctl(fd, pin_op, &range) == 0); // idempotent
    for (size_t i = 0; i < page * 5; ++i) assert(memory[i] == (char)(i % 251));
    assert(__macapk_arm64_ioctl(fd, pin_op, nullptr) == -1 && errno == EFAULT);
    assert(__macapk_arm64_ioctl(fd, pin_op, (void*)1) == -1 && errno == EFAULT);
    range = {1, (uint32_t)page};
    assert(__macapk_arm64_ioctl(fd, pin_op, &range) == -1 && errno == EINVAL);
    range = {0, 1};
    assert(__macapk_arm64_ioctl(fd, unpin_op, &range) == -1 && errno == EINVAL);
    range = {(uint32_t)(page * 4), (uint32_t)(page * 2)};
    assert(__macapk_arm64_ioctl(fd, unpin_op, &range) == -1 && errno == EINVAL);
    range = {0xffffc000, 0x8000};
    assert(__macapk_arm64_ioctl(fd, pin_op, &range) == -1 && errno == EINVAL);
    range = {0, 0};
    pid_t child = ::fork();
    assert(child >= 0);
    if (!child) ::_exit(__macapk_arm64_ioctl(alias, pin_op, &range) == -1 && errno == ENOTSUP ? 0 : 1);
    int status = 0;
    assert(::waitpid(child, &status, 0) == child && WIFEXITED(status) && WEXITSTATUS(status) == 0);
    assert(__macapk_arm64_close(fd) == 0);
    assert(__macapk_arm64_ioctl(alias, status_op, &range) == 1);
    assert(__macapk_arm64_close(alias) == 0);
    assert(__macapk_arm64_ioctl(alias, pin_op, &range) == -1);
    assert(__macapk_arm64_munmap(memory, page * 5) == 0);
    int ordinary = ::open("/dev/null", O_RDONLY);
    assert(ordinary >= 0 && __macapk_arm64_ioctl(ordinary, pin_op, &range) == -1);
    ::close(ordinary);
    fd = __macapk_arm64_ASharedMemory_create("short-cache", 4096);
    assert(fd >= 0 && __macapk_arm64_ioctl(fd, unpin_op, &range) == 0);
    assert(__macapk_arm64_ioctl(fd, pin_op, &range) == 0);
    assert(__macapk_arm64_close(fd) == 0);
}

int main() {
    const size_t page = (size_t)::sysconf(_SC_PAGESIZE);
    int fd = __macapk_arm64_ASharedMemory_create("region-test", page);
    assert(fd >= 0 && __macapk_arm64_ASharedMemory_getSize(fd) == page);
    if (!std::getenv("MACAPK_SHARED_MEMORY_REGIONS")) {
        assert(__macapk_arm64_ASharedMemory_setProt(fd, PROT_READ) == -1 && errno == ENOSYS);
        assert(__macapk_arm64_fcntl(fd, 1034, nullptr) == -1 && errno == EINVAL);
        assert(__macapk_arm64_close(fd) == 0);
        std::puts("shared regions: opt-out retained");
        return 0;
    }
    check_pin_ranges(page);
    assert(__macapk_arm64_ASharedMemory_create("empty", 0) == -1 && errno == EINVAL);
    assert(__macapk_arm64_fcntl(fd, 1034, nullptr) == -1 && errno == EINVAL); // ashmem, not memfd
    assert(__macapk_arm64_ioctl(fd, 0x7706, nullptr) == (PROT_READ | PROT_WRITE));
    assert(__macapk_arm64_ioctl(fd, 0x7704, nullptr) == (int)page);
    struct BionicStat { uint64_t dev, ino; uint32_t mode, nlink, uid, gid; uint64_t rdev; char rest[88]; };
    static_assert(sizeof(BionicStat) == 128);
    BionicStat device{}, region{};
    assert(__macapk_arm64_stat("/dev/ashmem", &device) == 0);
    assert(__macapk_arm64_fstat(fd, &region) == 0);
    assert((device.mode & S_IFMT) == S_IFCHR && (region.mode & S_IFMT) == S_IFCHR);
    assert(device.dev && device.dev == region.dev && device.rdev == region.rdev);
    assert(__macapk_arm64_ftruncate(fd, page * 2) == -1);
    assert(__macapk_arm64_pwrite(fd, "A", 1, 0) == 1);
    char* writer = (char*)__macapk_arm64_mmap(nullptr, page, PROT_READ | PROT_WRITE, 1, fd, 0);
    assert(writer != MAP_FAILED && writer[0] == 'A');
    int alias = __macapk_arm64_dup(fd);
    int cloexec = __macapk_arm64_fcntl(fd, 1030, nullptr);
    assert(alias >= 0 && cloexec >= 0 && (::fcntl(cloexec, F_GETFD) & FD_CLOEXEC));
    assert(!(__macapk_arm64_fcntl(alias, 1, nullptr) & FD_CLOEXEC));
    int host_alias = ::dup(fd); // e.g. a JNI-side ParcelFileDescriptor.dup
    assert(host_alias >= 0 && __macapk_arm64_fstat(host_alias, &region) == 0);

    // Cross-process protection metadata is explicitly unsupported, not copied
    // and advertised as coherent across parent/child changes.
    pid_t child = ::fork();
    assert(child >= 0);
    if (child == 0) {
        int result = __macapk_arm64_ASharedMemory_setProt(alias, PROT_READ);
        ::_exit(result == -1 && errno == ENOTSUP ? 0 : 1);
    }
    int status = 0;
    assert(::waitpid(child, &status, 0) == child && WIFEXITED(status) && WEXITSTATUS(status) == 0);

    assert(__macapk_arm64_ASharedMemory_setProt(alias, PROT_READ) == 0);
    assert(__macapk_arm64_ioctl(fd, 0x7706, nullptr) == PROT_READ);
    assert((::fcntl(fd, F_GETFL) & O_ACCMODE) == O_RDONLY);
    assert((::fcntl(alias, F_GETFL) & O_ACCMODE) == O_RDONLY);
    assert((::fcntl(cloexec, F_GETFL) & O_ACCMODE) == O_RDONLY);
    assert((::fcntl(host_alias, F_GETFL) & O_ACCMODE) == O_RDONLY);
    assert(::fcntl(cloexec, F_GETFD) & FD_CLOEXEC);
    assert(!(::fcntl(alias, F_GETFD) & FD_CLOEXEC));
    assert(__macapk_arm64_ASharedMemory_setProt(fd, PROT_READ | PROT_WRITE) == -1);
    assert(__macapk_arm64_pwrite(alias, "B", 1, 0) == -1);
    assert(__macapk_arm64_write(fd, "B", 1) == -1);
    iovec vector{(void*)"B", 1};
    assert(__macapk_arm64_writev(cloexec, &vector, 1) == -1);
    // Kernel enforcement matters even for calls bypassing the shim.
    assert(::pwrite(fd, "B", 1, 0) == -1);
    assert(::ftruncate(alias, page * 2) == -1);
    assert(::mmap(nullptr, page, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0) == MAP_FAILED);
    assert(__macapk_arm64_mmap(nullptr, page, PROT_READ | PROT_WRITE, 1, alias, 0) == MAP_FAILED);
    assert(__macapk_arm64_mmap(nullptr, page, PROT_READ | PROT_WRITE, 2, alias, 0) == MAP_FAILED);
    char* reader = (char*)__macapk_arm64_mmap(nullptr, page, PROT_READ, 1, fd, 0);
    assert(reader != MAP_FAILED && reader[0] == 'A');
    assert(::mprotect(reader, page, PROT_READ | PROT_WRITE) == -1);
    writer[0] = 'C'; // ashmem preserves the earlier writable mapping.
    assert(reader[0] == 'C');
    assert(__macapk_arm64_fcntl(fd, 1033, (void*)8) == -1); // unsupported F_SEAL_WRITE

    int replaced = ::open("/dev/null", O_RDONLY);
    assert(replaced >= 0 && __macapk_arm64_dup2(alias, replaced) == replaced);
    assert(__macapk_arm64_ioctl(replaced, 0x7706, nullptr) == PROT_READ);
    assert(__macapk_arm64_close(fd) == 0);
    assert(__macapk_arm64_close(alias) == 0);
    assert(__macapk_arm64_close(cloexec) == 0);
    assert(__macapk_arm64_close(host_alias) == 0);
    assert(__macapk_arm64_ioctl(replaced, 0x7706, nullptr) == PROT_READ);
    assert(__macapk_arm64_close(replaced) == 0);
    assert(__macapk_arm64_munmap(writer, page) == 0);
    assert(__macapk_arm64_munmap(reader, page) == 0);

    FILE* ordinary = ::tmpfile();
    assert(ordinary);
    assert(__macapk_arm64_fcntl(::fileno(ordinary), 1034, nullptr) == -1 && errno == EINVAL);
    assert(__macapk_arm64_ioctl(::fileno(ordinary), 0x7706, nullptr) == -1);
    assert(__macapk_arm64_ASharedMemory_getSize(::fileno(ordinary)) == 0);
    assert(__macapk_arm64_fstat(::fileno(ordinary), &region) == 0 && (region.mode & S_IFMT) == S_IFREG);
    assert(__macapk_arm64_ftruncate(::fileno(ordinary), page * 2) == 0);
    assert(__macapk_arm64_write(::fileno(ordinary), "ordinary", 8) == 8);
    ::fclose(ordinary);
    int partial = __macapk_arm64_ASharedMemory_create("partial-page", 4096);
    assert(partial >= 0 && __macapk_arm64_ASharedMemory_getSize(partial) == 4096);
    void* rounded = __macapk_arm64_mmap(nullptr, page, PROT_READ | PROT_WRITE, 1, partial, 0);
    assert(rounded != MAP_FAILED && __macapk_arm64_munmap(rounded, page) == 0);
    assert(__macapk_arm64_ioctl(partial, 0x40087705, (void*)(intptr_t)PROT_READ) == 0);
    assert(__macapk_arm64_ioctl(partial, 0x7706, nullptr) == PROT_READ);
    assert(__macapk_arm64_close(partial) == 0);
    std::puts("shared regions: pin/unpin ranges and retained data, aliases, kernel restrictions and lifecycle passed");
}
