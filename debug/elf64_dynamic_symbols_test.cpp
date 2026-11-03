// Standalone parser regression; no JNI or Android SDK needed.
#include "macapk/elf64_reader.h"
#include <cassert>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <iterator>

using namespace macapk::translator;
namespace fs = std::filesystem;

template<class T> void put(std::vector<uint8_t>& bytes, size_t at, T value) {
    assert(at <= bytes.size() && sizeof(value) <= bytes.size() - at);
    std::memcpy(bytes.data() + at, &value, sizeof(value));
}

int main(int argc, char** argv) {
    assert(argc >= 2);
    const fs::path fixtures(argv[1]);
    auto parse = [&](const fs::path& file) {
        Elf64Image image;
        auto error = read_elf64(file.string(), &image, false);
        if (!error.empty()) { std::cerr << error << '\n'; std::abort(); }
        return image;
    };
    for (auto name : {"libmacapk_fixture_one.so", "libmacapk_fixture_gnu.so"}) {
        auto image = parse(fixtures / name);
        assert(image.symbols.size() == 1);
        assert(image.symbols[0].name == "macapk_linkmap_fixture");
        assert(image.symbols[0].address == 0x200 && image.symbols[0].type == 2);
    }
    auto original = parse(fixtures / "libmacapk_fixture_one.so").raw;
    unsigned case_number = 0;
    auto check = [&](std::vector<uint8_t> bytes, bool success) {
        ++case_number;
        auto path = fixtures / "parser-mutated.so";
        std::ofstream output(path, std::ios::binary);
        output.write(reinterpret_cast<const char*>(bytes.data()), bytes.size());
        output.close();
        Elf64Image image;
        auto error = read_elf64(path.string(), &image, false);
        if (success) assert(error.empty() && image.symbols.size() == 1);
        else { if (error.empty()) std::cerr << "unexpected success in case " << case_number << "\n"; assert(!error.empty()); }
        fs::remove(path);
    };
    // Invalid optional sections must not hide a valid dynamic export table.
    auto damaged = original;
    put<uint64_t>(damaged, 40, UINT64_MAX);
    put<uint16_t>(damaged, 58, 64);
    put<uint16_t>(damaged, 60, 1);
    check(damaged, true);
    // A valid section-only ELF remains supported.
    auto sections = original;
    sections.resize(1280);
    put<uint16_t>(sections, 56, 1); // omit PT_DYNAMIC
    put<uint64_t>(sections, 40, 1024);
    put<uint16_t>(sections, 58, 64);
    put<uint16_t>(sections, 60, 3);
    Elf64Shdr dynsym{}, strings{};
    dynsym.sh_type = 11; dynsym.sh_offset = 0x280; dynsym.sh_size = 48; dynsym.sh_link = 2;
    strings.sh_type = 3; strings.sh_offset = 0x300; strings.sh_size = 32;
    put(sections, 1088, dynsym); put(sections, 1152, strings);
    check(sections, true);
    auto invalid = original;
    put<uint64_t>(invalid, 0x128, UINT64_MAX); // DT_SYMTAB
    check(invalid, false);
    invalid = original;
    put<uint32_t>(invalid, 0x244, UINT32_MAX); // DT_HASH nchain
    check(invalid, false);
    invalid = original;
    put<uint64_t>(invalid, 128, UINT64_MAX); // PT_DYNAMIC file offset
    check(invalid, false);
    invalid = original;
    for (size_t at = 0x100; at < 0x160; at += 16) put<int64_t>(invalid, at, 1);
    check(invalid, false); // no DT_NULL
    invalid = original;
    std::fill(invalid.begin() + 0x301, invalid.begin() + 0x318, 'x');
    check(invalid, false); // unterminated dynamic symbol name
    invalid = parse(fixtures / "libmacapk_fixture_gnu.so").raw;
    put<uint32_t>(invalid, 0x258, UINT32_MAX); // GNU bucket
    check(invalid, false);
    if (argc == 3) {
        auto actual = parse(argv[2]);
        assert(actual.symbols.size() == 6);
        bool onload = false;
        for (const auto& symbol : actual.symbols)
            if (symbol.name == "JNI_OnLoad") { assert(symbol.address == 0x574a8); onload = true; }
        assert(onload);
    }
    std::cout << "dynamic symbol parser: SysV/GNU, section fallback, malformed bounds passed\n";
}
