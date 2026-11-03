// JNI fixture for NativeTextureWindowSmoke; never loaded by an APK.
#include <jni.h>
#include <dlfcn.h>
#include <stdexcept>
#include <thread>
#include <exception>
#include <cstdlib>
#include <cstring>

template<class T> static T symbol(const char* name) {
    void* address = dlsym(RTLD_DEFAULT, name);
    if (!address) throw std::runtime_error(name);
    return reinterpret_cast<T>(address);
}
static void require(bool value, const char* message) {
    if (!value) throw std::runtime_error(message);
}
extern "C" JNIEXPORT void JNICALL Java_NativeTextureWindowSmoke_render(
        JNIEnv* env, jclass, jobject first, jobject second) {
    using Handle = void*;
    try {
        auto from = symbol<Handle(*)(void*, void*)>("__macapk_arm64_ANativeWindow_fromSurface");
        auto to = symbol<Handle(*)(void*, void*)>("__macapk_arm64_ANativeWindow_toSurface");
        auto acquire = symbol<void(*)(Handle)>("__macapk_arm64_ANativeWindow_acquire");
        auto release = symbol<void(*)(Handle)>("__macapk_arm64_ANativeWindow_release");
        auto width = symbol<int(*)(Handle)>("__macapk_arm64_ANativeWindow_getWidth");
        auto height = symbol<int(*)(Handle)>("__macapk_arm64_ANativeWindow_getHeight");
        Handle windows[] = {from(env, first), from(env, second)};
        require(windows[0] && windows[1] && windows[0] != windows[1], "window streams alias");
        require(from(env, first) == windows[0], "same Surface changed identity");
        acquire(windows[0]); release(windows[0]); release(windows[0]);
        jobject back = static_cast<jobject>(to(env, windows[0]));
        require(back && env->IsSameObject(back, first), "toSurface lost identity/refcount");
        env->DeleteLocalRef(back);
        require(width(windows[0]) == 3 && height(windows[0]) == 2 &&
                width(windows[1]) == 2 && height(windows[1]) == 3, "wrong window dimensions");
        std::exception_ptr failure;
        std::thread renderer([&] { try {
        auto open = symbol<Handle(*)(const char*, int)>("__macapk_arm64_dlopen");
        auto lookup = symbol<Handle(*)(Handle, const char*)>("__macapk_arm64_dlsym");
        Handle egl = open("libEGL.so", 2);
        require(lookup(egl, "glBlitFramebuffer") ==
                symbol<Handle>("__macapk_arm64_glBlitFramebuffer"), "dlsym bypassed stack ABI bridge");
        auto display = symbol<Handle(*)(Handle)>("eglGetDisplay")(nullptr);
        require(symbol<unsigned(*)(Handle,int*,int*)>("__macapk_arm64_eglInitialize")(
                    display,nullptr,nullptr), "EGL display initialization failed");
        auto query = symbol<const char*(*)(Handle,int)>("__macapk_arm64_eglQueryString");
        require(lookup(egl,"eglQueryString") == reinterpret_cast<Handle>(query),
                "dlsym bypassed EGL capability policy");
        require(symbol<Handle(*)(const char*)>("__macapk_arm64_eglGetProcAddress")("eglQueryString")
                    == reinterpret_cast<Handle>(query), "proc lookup bypassed EGL capability policy");
        const char* host_extensions = symbol<const char*(*)(Handle,int)>("eglQueryString")(display,0x3055);
        const char* extensions = query(display,0x3055);
        require(host_extensions && extensions, "EGL extensions unavailable");
        const char* policy = std::getenv("MACAPK_EGL_SURFACELESS_CONTEXT");
        if (policy && std::strcmp(policy,"0") == 0) {
            require(!std::strstr(extensions,"EGL_KHR_surfaceless_context"), "surfaceless opt-out ignored");
            require(std::strstr(extensions,"EGL_KHR_create_context"), "unrelated EGL capability removed");
            const char* saved = extensions;
            query(nullptr,0x3055);
            require(query(display,0x3055) == saved, "EGL extension string lifetime changed");
        } else {
            require(std::strcmp(extensions,host_extensions) == 0, "default EGL capabilities changed");
        }
        auto choose = symbol<unsigned(*)(Handle,const int*,Handle*,int,int*)>("eglChooseConfig");
        const int attrs[] = {0x3024,8,0x3023,8,0x3022,8,0x3021,8,0x3033,1,0x3040,0x40,0x3038};
        Handle config = nullptr; int count = 0;
        require(choose(display,attrs,&config,1,&count) && count, "no real EGL config");
        const int context_attrs[] = {0x3098,3,0x3038};
        auto context = symbol<Handle(*)(Handle,Handle,Handle,const int*)>("eglCreateContext")(
            display, config, nullptr, context_attrs);
        require(context, "real EGL context creation failed");
        auto create = reinterpret_cast<Handle(*)(Handle,Handle,Handle,const int*)>(
            lookup(egl,"eglCreateWindowSurface"));
        auto current = symbol<unsigned(*)(Handle,Handle,Handle,Handle)>("__macapk_arm64_eglMakeCurrent");
        auto swap = reinterpret_cast<unsigned(*)(Handle,Handle,const int*,int)>(
            lookup(egl,"eglSwapBuffersWithDamageKHR"));
        auto destroy = reinterpret_cast<unsigned(*)(Handle,Handle)>(lookup(egl,"eglDestroySurface"));
        auto clear_color = symbol<void(*)(float,float,float,float)>("glClearColor");
        auto clear = symbol<void(*)(unsigned)>("glClear");
        auto generate_texture = symbol<void(*)(int,unsigned*)>("glGenTextures");
        auto bind_texture = symbol<void(*)(unsigned,unsigned)>("glBindTexture");
        auto texture_image = symbol<void(*)(unsigned,int,int,int,int,int,unsigned,unsigned,const void*)>("glTexImage2D");
        auto generate_fbo = symbol<void(*)(int,unsigned*)>("glGenFramebuffers");
        auto bind_fbo = symbol<void(*)(unsigned,unsigned)>("glBindFramebuffer");
        auto attach = symbol<void(*)(unsigned,unsigned,unsigned,unsigned,int)>("glFramebufferTexture2D");
        auto blit = reinterpret_cast<void(*)(int,int,int,int,int,int,int,int,uint64_t,uint64_t)>(
            lookup(egl,"glBlitFramebuffer"));
        for (int i=0; i<2; ++i) {
            Handle surface = create(display,config,windows[i],nullptr);
            require(surface && current(display,surface,surface,context), "stream surface refused");
            require(!swap(display,surface,nullptr,-1) &&
                symbol<unsigned(*)()>("__macapk_arm64_eglGetError")()!=0x3000,
                "invalid damage count was accepted");
            unsigned texture=0, fbo=0;
            generate_texture(1,&texture); bind_texture(0x0de1,texture);
            texture_image(0x0de1,0,0x8058,width(windows[i]),height(windows[i]),0,0x1908,0x1401,nullptr);
            generate_fbo(1,&fbo); bind_fbo(0x8d40,fbo); attach(0x8d40,0x8ce0,0x0de1,texture,0);
            require(symbol<unsigned(*)(unsigned)>("glCheckFramebufferStatus")(0x8d40)==0x8cd5, "incomplete source framebuffer");
            clear_color(i==0?.5f:0.f,i==1?.5f:0.f,0.f,.5f); clear(0x4000);
            unsigned copied=0;
            generate_texture(1,&copied); bind_texture(0x0de1,copied);
            auto copy_full = reinterpret_cast<void(*)(unsigned,int,unsigned,unsigned,int,int,unsigned,
                    unsigned char,uint64_t,uint64_t)>(lookup(egl,"glCopyTextureCHROMIUM"));
            require(copy_full, "CHROMIUM full texture copy unavailable");
            copy_full(texture,0,0x0de1,copied,0,0x1908,0x1401,0,0,1);
            attach(0x8d40,0x8ce0,0x0de1,copied,0);
            unsigned char pixel[4]{};
            auto read_pixel = symbol<void(*)(int,int,int,int,unsigned,unsigned,void*)>("glReadPixels");
            read_pixel(1,1,1,1,0x1908,0x1401,pixel);
            require(pixel[0]==(i==0?255:0) && pixel[1]==(i==1?255:0) && pixel[3]==128,
                "Android stack arguments corrupted the CHROMIUM alpha flag");
            attach(0x8d40,0x8ce0,0x0de1,texture,0);
            clear_color(i==0?1.f:0.f,i==1?1.f:0.f,0.f,1.f); clear(0x4000);
            texture_image(0x0de1,0,0x8058,width(windows[i]),height(windows[i]),0,0x1908,0x1401,nullptr);
            auto copy = reinterpret_cast<void(*)(unsigned,int,unsigned,unsigned,int,int,int,int,
                    uint64_t,uint64_t,uint64_t,uint64_t,uint64_t,uint64_t)>(
                lookup(egl,"glCopySubTextureCHROMIUM"));
            require(copy, "CHROMIUM texture copy unavailable");
            copy(texture,0,0x0de1,copied,0,0,0,0,0,width(windows[i]),height(windows[i]),0,0,0);
            attach(0x8d40,0x8ce0,0x0de1,copied,0);
            read_pixel(1,1,1,1,0x1908,0x1401,pixel);
            require(pixel[0]==(i==0?255:0) && pixel[1]==(i==1?255:0) && pixel[3]==255,
                "Android stack arguments corrupted the CHROMIUM texture copy");
            bind_fbo(0x8ca9,0); // DRAW=window, READ=colored offscreen source.
            blit(0,0,width(windows[i]),height(windows[i]),0,0,width(windows[i]),height(windows[i]),0x4000,0x2600);
            require(symbol<unsigned(*)()>("glGetError")()==0, "Android stack arguments corrupted the blit");
            require(swap(display,surface,nullptr,0), "native frame swap failed");
            symbol<void(*)(int,const unsigned*)>("glDeleteFramebuffers")(1,&fbo);
            symbol<void(*)(int,const unsigned*)>("glDeleteTextures")(1,&texture);
            symbol<void(*)(int,const unsigned*)>("glDeleteTextures")(1,&copied);
            require(current(display,nullptr,nullptr,nullptr) && destroy(display,surface), "stream destruction failed");
            release(windows[i]);
        }
        symbol<unsigned(*)(Handle,Handle)>("eglDestroyContext")(display,context);
        symbol<unsigned(*)(Handle)>("__macapk_arm64_eglTerminate")(display);
        } catch (...) { failure = std::current_exception(); } });
        renderer.join();
        if (failure) std::rethrow_exception(failure);
    } catch (const std::exception& error) {
        env->ThrowNew(env->FindClass("java/lang/AssertionError"), error.what());
    }
}
