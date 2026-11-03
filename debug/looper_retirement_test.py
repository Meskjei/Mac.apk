#!/usr/bin/env python3
"""Exercise the production looper ownership block without loading an APK/JVM."""
import pathlib, subprocess, tempfile, sys
source = pathlib.Path(sys.argv[1] if len(sys.argv)>1 else 'runtime/translator/src/bionic_shim.cpp').read_text()
block = source[source.index('struct MacApkChoreoCb {'):source.index('void macapk_choreo_register_on(')]
check = r'''
int descriptors() { int n=0; for(int f=0;f<4096;++f) n += fcntl(f,F_GETFD)>=0; return n; }
int main() {
    int before=descriptors();
    for(int i=0;i<512;++i) std::thread([] {
        auto* a=macapk_looper_ensure();
        assert(a==macapk_looper_ensure());
        assert(a->wake_r>=0 && a->wake_w>=0);
    }).join();
    assert(g_loopers.empty());
    assert(descriptors()==before);
'''
if 'struct MacApkNativePoll {' in block:
    check += r'''
    std::promise<MacApkLooper*> ready; std::promise<void> finish;
    auto stop=finish.get_future();
    int pipe_fds[2]; assert(pipe(pipe_fds)==0);
    std::thread owner([&] {
        auto* l=macapk_looper_ensure();
        l->fds.push_back({pipe_fds[0],0,1,nullptr,nullptr});
        ready.set_value(l); stop.wait();
    });
    auto* l=ready.get_future().get();
    auto active=std::make_unique<MacApkNativePoll>(l);
    finish.set_value(); owner.join();
    assert(g_loopers.empty() && l->retired && l->fds.empty());
    assert(l->wake_r>=0 && l->wake_w>=0); // a foreign poll still owns the pipe
    active.reset();
    assert(l->wake_r==-1 && l->wake_w==-1);
    assert(fcntl(pipe_fds[0],F_GETFD)>=0); // registered fds belong to the caller
    MacApkNativePoll late(l); assert(!late.looper);
    close(pipe_fds[0]); close(pipe_fds[1]);
    assert(descriptors()==before);
'''
check += 'puts("Looper retirement: thread identity, registry and pipe cleanup, active poll lifetime passed");}\n'
headers = '\n'.join('#include <'+h+'>' for h in ['algorithm','atomic','cassert','condition_variable','cstdint','cstdio','cstdlib','fcntl.h','future','memory','mutex','thread','unistd.h','vector'])
with tempfile.TemporaryDirectory() as d:
    cpp=pathlib.Path(d)/'test.cpp';exe=pathlib.Path(d)/'test';cpp.write_text(headers+'\n'+block+check)
    subprocess.run(['clang++','-std=c++17','-pthread',str(cpp),'-o',str(exe)],check=True)
    subprocess.run([str(exe)],check=True)
