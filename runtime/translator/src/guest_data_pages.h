#pragma once

#include <algorithm>
#include <cstddef>
#include <cstdint>
#include <cstdlib>
#include <limits>
#include <iterator>
#include <map>
#include <mutex>

// Experimental data commits only. Host-page rounding weakens subpage guard
// boundaries; keep it opt-in until a full guest-page protection model exists.
namespace macapk_guest_pages {
inline bool enabled() {
    static const bool on = [] {
        const char* value = std::getenv("MACAPK_SUBPAGE_RW_COMPAT");
        return value && value[0] == '1' && value[1] == '\0';
    }();
    return on;
}

struct Range { uintptr_t lo, hi; };

inline bool covered(uintptr_t address, size_t length, size_t page, Range& out) {
    const auto max = std::numeric_limits<uintptr_t>::max();
    if (!length || !page || (page & (page - 1)) || length > max - address) return false;
    const uintptr_t end = address + length;
    if (end > max - (page - 1)) return false;
    out = {address & ~(uintptr_t(page) - 1), (end + page - 1) & ~(uintptr_t(page) - 1)};
    return out.hi > out.lo;
}

class Reservations {
    std::map<uintptr_t, uintptr_t> ranges;
public:
    void remove(Range r) {
        auto it = ranges.upper_bound(r.lo);
        if (it != ranges.begin()) --it;
        while (it != ranges.end() && it->first < r.hi) {
            const Range old{it->first, it->second};
            if (old.hi <= r.lo) { ++it; continue; }
            it = ranges.erase(it);
            if (old.lo < r.lo) ranges.emplace(old.lo, r.lo);
            if (old.hi > r.hi) { ranges.emplace(r.hi, old.hi); break; }
        }
    }
    void add(Range r) { remove(r); ranges.emplace(r.lo, r.hi); }
    bool contains(Range r) const {
        const auto it = ranges.upper_bound(r.lo);
        if (it == ranges.begin()) return false;
        const auto before = std::prev(it);
        return r.lo < r.hi && before->first <= r.lo && before->second >= r.hi;
    }
};

struct State { std::mutex mutex; Reservations reservations; };
inline State& state() { static State s; return s; }

inline void forget(void* address, size_t length, size_t page) {
    if (!enabled()) return;
    Range r;
    if (!covered(reinterpret_cast<uintptr_t>(address), length, page, r)) return;
    auto& s = state();
    std::lock_guard<std::mutex> lock(s.mutex);
    s.reservations.remove(r);
}
}
