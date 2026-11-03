#include <cassert>
#include <cstdio>
#include <filesystem>
#include <fstream>
#include <unistd.h>

extern "C" void __macapk_set_android_root(const char*);
extern "C" FILE* __macapk_arm64_fopen(const char*, const char*);
extern "C" void* __macapk_arm64_opendir(const char*);
extern "C" int __macapk_arm64_closedir(void*);

int main() {
    char pattern[] = "/tmp/macapk-platform-test-XXXXXX";
    char* root = mkdtemp(pattern);
    assert(root);
    namespace fs = std::filesystem;
    for (const char* prefix : {"/system/lib64", "/apex/com.android.i18n/etc/icu"}) {
        fs::create_directories(std::string(root) + prefix);
        std::ofstream(std::string(root) + prefix + "/fixture") << "actual file";
    }
    __macapk_set_android_root(root);
    for (const char* prefix : {"/system/lib64", "/apex/com.android.i18n/etc/icu"}) {
        void* directory = __macapk_arm64_opendir(prefix);
        assert(directory && __macapk_arm64_closedir(directory) == 0);
        FILE* file = __macapk_arm64_fopen((std::string(prefix) + "/fixture").c_str(), "r");
        assert(file && fgetc(file) == 'a' && fclose(file) == 0);
    }
    assert(!__macapk_arm64_fopen("/apex/com.android.i18n/etc/icu/missing", "r"));
    fs::remove_all(root);
    std::puts("platform files: real system/APEX directory and file access passed");
}
