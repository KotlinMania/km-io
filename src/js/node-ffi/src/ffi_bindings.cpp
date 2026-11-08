#include <napi.h>

#ifdef _WIN32
#include <process.h>
#else
#include <unistd.h>
#endif

Napi::Object Init(Napi::Env env, Napi::Object exports) {
    exports.Set("getpid", Napi::Function::New(env, [](const Napi::CallbackInfo& info) -> Napi::Value {
#ifdef _WIN32
        return Napi::Number::New(info.Env(), ::_getpid());
#else
        return Napi::Number::New(info.Env(), ::getpid());
#endif
    }));
    return exports;
}

NODE_API_MODULE(ffi_native, Init)
