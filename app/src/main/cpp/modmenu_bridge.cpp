// Our bridge into the Preloader mod-menu ABI.
//
// Mod menu ABI: LiteLDev (preloader-android), used with permission.
//
// The floating ball and the launcher's shader screen both need to open the menu
// the mod registers into. That menu belongs to this ABI, not to the mod, so the
// call goes through here rather than into the mod's library.

#include <jni.h>
#include <dlfcn.h>
#include <pl/ModMenu.hpp>

extern "C" JNIEXPORT void JNICALL
Java_dev_vela_app_ModMenuBridge_nativeOpenMenu(JNIEnv*, jclass) {
    pl::modmenu::requestOpenMenu();
}

extern "C" JNIEXPORT void JNICALL
Java_dev_vela_app_ModMenuBridge_nativeCloseMenu(JNIEnv*, jclass) {
    pl::modmenu::requestCloseMenu();
}

extern "C" JNIEXPORT void JNICALL
Java_dev_vela_app_ModMenuBridge_nativeSetModuleEnabled(
        JNIEnv* env, jclass, jstring moduleId, jboolean enabled) {
    if (moduleId == nullptr) return;
    const char* utf = env->GetStringUTFChars(moduleId, nullptr);
    if (utf == nullptr) return;
    pl::modmenu::setModuleEnabled(std::string_view(utf), enabled == JNI_TRUE);
    env->ReleaseStringUTFChars(moduleId, utf);
}

// The SDK reaches the host Activity through a JNI entry named for another
// package. Rather than editing their code or adopting their package name, we
// look their exported JNI function up in our own process and call it. Their
// source stays untouched; ours owns the hand-off.
extern "C" JNIEXPORT void JNICALL
Java_dev_vela_app_ModMenuBridge_nativeSetActivity(JNIEnv* env, jclass, jobject activity) {
    using SetActivityFn = void (*)(JNIEnv*, jclass, jobject);
    static SetActivityFn fn = reinterpret_cast<SetActivityFn>(
            dlsym(RTLD_DEFAULT,
                  "Java_org_levimc_launcher_preloader_PreloaderInput_nativeSetActivity"));
    if (fn != nullptr) {
        fn(env, nullptr, activity);
    }
}

// Reports whether the ABI answered at all, so the ball can say "not ready"
// instead of appearing to work.
extern "C" JNIEXPORT jboolean JNICALL
Java_dev_vela_app_ModMenuBridge_nativeIsAvailable(JNIEnv*, jclass) {
    return JNI_TRUE;
}
