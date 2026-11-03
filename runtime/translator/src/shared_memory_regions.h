#pragma once

#include <algorithm>
#include <cerrno>
#include <climits>
#include <cstdint>
#include <cstring>
#include <cstdlib>
#include <fcntl.h>
#include <limits>
#include <iterator>
#include <map>
#include <memory>
#include <mutex>
#include <pthread.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/uio.h>
#include <unistd.h>
#include <mach/mach.h>
#include <mach/mach_vm.h>

namespace macapk_shared_memory {

inline bool enabled() {
    static const bool value = [] { const char* e = std::getenv("MACAPK_SHARED_MEMORY_REGIONS");
        return e && e[0] == '1' && e[1] == '\0'; }();
    return value;
}

struct Region {
    dev_t device;
    ino_t inode;
    off_t size;
    pid_t owner;
    int read_fd = -1;
    int protection = PROT_READ | PROT_WRITE;
    // Half-open byte ranges. Aliases share both protection and pin state.
    std::map<uint64_t, uint64_t> unpinned;
    ~Region() {
        struct stat stat{};
        if (read_fd >= 0 && ::fstat(read_fd, &stat) == 0 && stat.st_dev == device
                && stat.st_ino == inode && (::fcntl(read_fd, F_GETFL) & O_ACCMODE) == O_RDONLY)
            ::close(read_fd);
    }
};
struct Registry {
    std::mutex mutex;
    std::map<int, std::shared_ptr<Region>> fds;
    int fork_error;
    Registry();
};
inline Registry* fork_registry = nullptr;
inline Registry::Registry() {
    fork_registry = this;
    fork_error = ::pthread_atfork([] { fork_registry->mutex.lock(); },
        [] { fork_registry->mutex.unlock(); }, [] { fork_registry->mutex.unlock(); });
}
inline Registry& registry() { static Registry value; return value; }

// All callers hold the registry lock. Validate identity after host-side close/reuse.
inline std::shared_ptr<Region> find(int fd) {
    auto& fds = registry().fds;
    auto it = fds.find(fd);
    struct stat stat{};
    if (it == fds.end()) {
        if (::fstat(fd, &stat) != 0) return {};
        // JNI/SCM_RIGHTS may duplicate the descriptor outside the guest dup shim.
        for (const auto& entry : fds) if (stat.st_dev == entry.second->device && stat.st_ino == entry.second->inode) {
            auto region = entry.second;
            fds[fd] = region;
            return region;
        }
        return {};
    }
    if (::fstat(fd, &stat) == 0 && stat.st_dev == it->second->device && stat.st_ino == it->second->inode)
        return it->second;
    fds.erase(it);
    return {};
}

inline bool local(const Region& region) {
    // shortcut: metadata is process-local; inherited descriptors fail explicitly
    // until a real cross-process region/IPC backend shares protection changes.
    if (region.owner == ::getpid()) return true;
    errno = ENOTSUP;
    return false;
}

inline int create(off_t size) {
    if (registry().fork_error) { errno = registry().fork_error; return -1; }
    char name[] = "/tmp/macapk_shared_region_XXXXXX";
    int fd = ::mkstemp(name);
    if (fd < 0) return -1;
    int reader = ::open(name, O_RDONLY | O_CLOEXEC);
    int error = errno;
    ::unlink(name);
    if (reader < 0 || ::ftruncate(fd, size) != 0 || ::fcntl(fd, F_SETFD, FD_CLOEXEC) != 0) {
        if (reader >= 0) { error = errno; ::close(reader); }
        ::close(fd); errno = error; return -1;
    }
    struct stat stat{};
    if (::fstat(fd, &stat) != 0) {
        error = errno; ::close(reader); ::close(fd); errno = error; return -1;
    }
    auto region = std::make_shared<Region>();
    region->device = stat.st_dev; region->inode = stat.st_ino;
    region->size = size; region->owner = ::getpid(); region->read_fd = reader;
    std::lock_guard<std::mutex> lock(registry().mutex);
    registry().fds[fd] = std::move(region);
    return fd;
}

inline void alias(int oldfd, int newfd) {
    if (!enabled() || newfd < 0 || oldfd == newfd) return;
    std::lock_guard<std::mutex> lock(registry().mutex);
    auto region = find(oldfd);
    if (region) registry().fds[newfd] = std::move(region);
    else registry().fds.erase(newfd);
}

inline int close(int fd) {
    if (!enabled()) return ::close(fd);
    std::lock_guard<std::mutex> lock(registry().mutex);
    int result = ::close(fd);
    if (result == 0) registry().fds.erase(fd);
    return result;
}

inline int get_prot(int fd) {
    std::lock_guard<std::mutex> lock(registry().mutex);
    auto region = find(fd);
    if (!region) { errno = EINVAL; return -1; }
    return local(*region) ? region->protection : -1;
}

inline int set_prot(int fd, int prot) {
    if (!enabled()) { errno = ENOSYS; return -1; }
    // shortcut: only data-region R/RW modes are implemented; reject executable
    // or inaccessible regions until their full mapping/descriptor contract exists.
    if (prot != PROT_READ && prot != (PROT_READ | PROT_WRITE)) { errno = EINVAL; return -1; }
    std::lock_guard<std::mutex> lock(registry().mutex);
    auto region = find(fd);
    if (!region) { errno = EINVAL; return -1; }
    if (!local(*region)) return -1;
    if (prot & ~region->protection) { errno = EINVAL; return -1; }
    if (prot == PROT_READ && (region->protection & PROT_WRITE)) {
        struct stat reader{};
        if (::fstat(region->read_fd, &reader) != 0 || reader.st_dev != region->device
                || reader.st_ino != region->inode
                || (::fcntl(region->read_fd, F_GETFL) & O_ACCMODE) != O_RDONLY) {
            errno = EBADF; return -1;
        }
        // Replace every tracked alias with a genuinely read-only descriptor.
        // Existing writable mappings remain usable, as with ashmem; new
        // shared mappings, write/pwrite/stdio and truncation fail in the kernel.
        for (const auto& alias : registry().fds) {
            if (alias.second != region) continue;
            struct stat stat{};
            if (::fstat(alias.first, &stat) != 0 || stat.st_dev != region->device || stat.st_ino != region->inode) continue;
            int flags = ::fcntl(alias.first, F_GETFD);
            if (flags < 0 || ::dup2(region->read_fd, alias.first) < 0
                    || ::fcntl(alias.first, F_SETFD, flags) < 0) return -1;
        }
    }
    region->protection = prot;
    return 0;
}

inline off_t get_size(int fd) {
    std::lock_guard<std::mutex> lock(registry().mutex);
    auto region = find(fd);
    if (!region) { errno = EINVAL; return -1; }
    return local(*region) ? region->size : -1;
}

inline int pin_range(int fd, uint32_t command, const void* argument) {
    struct Pin { uint32_t offset, length; } pin{};
    mach_vm_size_t copied = 0;
    if (!argument || ::mach_vm_read_overwrite(mach_task_self(),
            (mach_vm_address_t)argument, sizeof(pin), (mach_vm_address_t)&pin,
            &copied) != KERN_SUCCESS || copied != sizeof(pin)) {
        errno = EFAULT; return -1;
    }
    std::lock_guard<std::mutex> lock(registry().mutex);
    auto region = find(fd);
    if (!region) { errno = EINVAL; return -1; }
    if (!local(*region)) return -1;
    const uint64_t page = (uint64_t)::sysconf(_SC_PAGESIZE);
    const uint64_t size = ((uint64_t)region->size + page - 1) / page * page;
    const uint64_t start = pin.offset;
    const uint64_t length = pin.length ? pin.length : (start < size ? size - start : 0);
    const uint64_t end = start + length;
    if (!length || (start % page) || (length % page) || end > UINT32_MAX || end > size) {
        errno = EINVAL; return -1;
    }
    auto& ranges = region->unpinned;
    auto it = ranges.upper_bound(start);
    if (it != ranges.begin()) {
        auto previous = std::prev(it);
        if (previous->second >= start) it = previous;
    }
    if (command == 0x7709) { // ASHMEM_GET_PIN_STATUS: any unpinned page => 0.
        for (; it != ranges.end() && it->first < end; ++it)
            if (it->second > start) return 0;
        return 1;
    }
    if (command == 0x40087708) { // ASHMEM_UNPIN: union overlapping/adjacent ranges.
        uint64_t first = start, last = end;
        while (it != ranges.end() && it->first <= last) {
            if (it->second < first) { ++it; continue; }
            first = std::min(first, it->first);
            last = std::max(last, it->second);
            it = ranges.erase(it);
        }
        ranges[first] = last;
        return 0;
    }
    if (command != 0x40087707) { errno = ENOTTY; return -1; }
    // ASHMEM_PIN: remove the requested interval, splitting a range if needed.
    while (it != ranges.end() && it->first < end) {
        const auto first = it->first, last = it->second;
        if (last <= start) { ++it; continue; }
        it = ranges.erase(it);
        if (first < start) ranges[first] = start;
        if (last > end) { ranges[end] = last; break; }
    }
    // This backend uses a durable unlinked file, not a volatile purgeable VM
    // object. Unpin permits reclamation but does not require it: data is retained,
    // so a successful re-pin truthfully returns ASHMEM_NOT_PURGED. Do not claim
    // WAS_PURGED or implement PURGE_ALL_CACHES without actually discarding data.
    return 0;
}

inline void device_metadata(struct stat& stat) {
    // This is a runtime-backed virtual ashmem device, not a host /dev node.
    stat.st_mode = S_IFCHR | 0666;
    stat.st_dev = stat.st_rdev = 0x6d610001;
    stat.st_nlink = 1;
}

inline bool stat_device(const char* path, struct stat& stat) {
    if (!enabled() || !path || std::strcmp(path, "/dev/ashmem") != 0) return false;
    std::memset(&stat, 0, sizeof(stat));
    device_metadata(stat);
    stat.st_blksize = ::sysconf(_SC_PAGESIZE);
    return true;
}

inline void stat_region(int fd, struct stat& stat) {
    if (!enabled()) return;
    std::lock_guard<std::mutex> lock(registry().mutex);
    if (find(fd)) device_metadata(stat);
}

inline void* map(void* addr, size_t length, int prot, int flags, int fd, off_t offset) {
    if (!enabled() || fd < 0) return ::mmap(addr, length, prot, flags, fd, offset);
    std::lock_guard<std::mutex> lock(registry().mutex);
    auto region = find(fd);
    if (!region) return ::mmap(addr, length, prot, flags, fd, offset);
    if (!local(*region)) return MAP_FAILED;
    const int maximum = region->protection;
    const uint64_t page = (uint64_t)::sysconf(_SC_PAGESIZE);
    const uint64_t mapped_size = ((uint64_t)region->size + page - 1) / page * page;
    if ((prot & ~maximum) || offset < 0 || (uint64_t)offset >= mapped_size
            || length > mapped_size - (uint64_t)offset) {
        errno = EACCES; return MAP_FAILED;
    }
    void* result = ::mmap(addr, length, prot, flags, fd, offset);
    if (result == MAP_FAILED) return result;
    // Limit the kernel's maximum permission as well as the initial permission:
    // even a direct host mprotect cannot upgrade a newly sealed shared mapping.
    if (::mach_vm_protect(mach_task_self(), (mach_vm_address_t)result,
            (mach_vm_size_t)length, TRUE, maximum) != KERN_SUCCESS) {
        ::munmap(result, length); errno = EACCES; return MAP_FAILED;
    }
    return result;
}

template<class Operation> inline ssize_t write(int fd, size_t count, off_t offset, Operation operation) {
    if (!enabled()) return operation();
    std::lock_guard<std::mutex> lock(registry().mutex);
    auto region = find(fd);
    if (!region) return operation();
    if (!local(*region)) return -1;
    if (!(region->protection & PROT_WRITE)) { errno = EPERM; return -1; }
    if (offset == -1) offset = ::lseek(fd, 0, SEEK_CUR);
    if (offset < 0 || (uint64_t)offset > (uint64_t)region->size
            || count > (uint64_t)region->size - (uint64_t)offset) { errno = EPERM; return -1; }
    return operation();
}

inline ssize_t writev(int fd, const struct iovec* vectors, int count) {
    if (!enabled()) return ::writev(fd, vectors, count);
    {
        std::lock_guard<std::mutex> lock(registry().mutex);
        if (!find(fd)) return ::writev(fd, vectors, count);
    }
    if (!vectors || count < 0 || count > IOV_MAX) { errno = EINVAL; return -1; }
    size_t total = 0;
    for (int i = 0; i < count; ++i) {
        if (vectors[i].iov_len > std::numeric_limits<size_t>::max() - total) { errno = EINVAL; return -1; }
        total += vectors[i].iov_len;
    }
    return write(fd, total, -1, [&] { return ::writev(fd, vectors, count); });
}

inline int truncate(int fd, off_t length) {
    if (!enabled()) return ::ftruncate(fd, length);
    std::lock_guard<std::mutex> lock(registry().mutex);
    auto region = find(fd);
    if (region && (!local(*region) || length != region->size)) { errno = EPERM; return -1; }
    return ::ftruncate(fd, length);
}

} // namespace macapk_shared_memory
