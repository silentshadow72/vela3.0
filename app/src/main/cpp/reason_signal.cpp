// Native signal handler for the reason log.
//
// Purpose: when the process dies in native code, Java cannot catch it and the
// platform's tombstone is unreadable without root. This writes one line to an
// already-open file descriptor and then lets the process die as it would have.
//
// Rules followed here, because a signal handler runs with a broken stack and a
// possibly corrupted heap:
//   - the file descriptor is opened once, at install time, never in the handler
//   - no malloc, no JNI, no logging framework, no stdio, no snprintf
//   - a fixed static buffer, manual integer formatting
//   - only open/write/close/raise/signal, which are async-signal-safe
//   - the default handler is restored and the signal re-raised, so the process
//     still dies and the system still records it

#include <jni.h>
#include <signal.h>
#include <unistd.h>
#include <fcntl.h>
#include <string.h>
#include <stdint.h>
#include <stdlib.h>
#include <time.h>

namespace {

int g_fd = -1;

// 512 bytes is enough for the longest line written here and small enough to sit
// in .bss without touching the heap.
char g_buffer[512];

// Async-signal-safe unsigned to decimal. No snprintf: it is not on the list.
int appendUnsigned(char* out, int pos, unsigned long long value) {
    char digits[24];
    int count = 0;
    if (value == 0) {
        out[pos++] = '0';
        return pos;
    }
    while (value > 0 && count < 24) {
        digits[count++] = static_cast<char>('0' + (value % 10));
        value /= 10;
    }
    while (count > 0) {
        out[pos++] = digits[--count];
    }
    return pos;
}

int appendHex(char* out, int pos, unsigned long long value) {
    const char* hex = "0123456789abcdef";
    out[pos++] = '0';
    out[pos++] = 'x';
    char digits[16];
    int count = 0;
    if (value == 0) {
        out[pos++] = '0';
        return pos;
    }
    while (value > 0 && count < 16) {
        digits[count++] = hex[value & 0xF];
        value >>= 4;
    }
    while (count > 0) {
        out[pos++] = digits[--count];
    }
    return pos;
}

int appendLiteral(char* out, int pos, const char* text) {
    while (*text != '\0' && pos < 500) {
        out[pos++] = *text++;
    }
    return pos;
}

void writeLine(const char* signalName, int signalNumber, const void* address) {
    if (g_fd < 0) return;

    // Manual ISO-8601-ish timestamp from time(), avoiding localtime's allocation.
    time_t now = time(nullptr);
    int pos = 0;
    pos = appendLiteral(g_buffer, pos, "{\"ts\":");
    pos = appendUnsigned(g_buffer, pos, static_cast<unsigned long long>(now));
    pos = appendLiteral(g_buffer, pos, ",\"kind\":\"native\",\"signal\":");
    pos = appendUnsigned(g_buffer, pos, static_cast<unsigned long long>(signalNumber));
    pos = appendLiteral(g_buffer, pos, ",\"signalName\":\"");
    pos = appendLiteral(g_buffer, pos, signalName);
    pos = appendLiteral(g_buffer, pos, "\",\"fault\":\"");
    pos = appendHex(g_buffer, pos, reinterpret_cast<unsigned long long>(address));
    pos = appendLiteral(g_buffer, pos, "\",\"question\":\"what was running when this died\",");
    pos = appendLiteral(g_buffer, pos, "\"attempt\":\"native call in progress\",");
    pos = appendLiteral(g_buffer, pos, "\"observation\":\"native signal raised\",");
    pos = appendLiteral(g_buffer, pos, "\"conclusion\":\"the last stage line above names the call\"}\n");

    ssize_t ignored = write(g_fd, g_buffer, static_cast<size_t>(pos));
    (void) ignored;
    // Best effort: the fd is opened O_APPEND, and fsync is async-signal-safe.
    fsync(g_fd);
}

void handler(int signalNumber, siginfo_t* info, void* /*context*/) {
    const char* name = "UNKNOWN";
    switch (signalNumber) {
        case SIGSEGV: name = "SIGSEGV"; break;
        case SIGABRT: name = "SIGABRT"; break;
        case SIGILL: name = "SIGILL"; break;
        case SIGBUS: name = "SIGBUS"; break;
        case SIGFPE: name = "SIGFPE"; break;
        default: break;
    }
    writeLine(name, signalNumber, info != nullptr ? info->si_addr : nullptr);

    // Let the process die the way it would have without us, so the system still
    // reports the crash and nothing is swallowed.
    signal(signalNumber, SIG_DFL);
    raise(signalNumber);
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_dev_vela_app_ReasonSignal_nativeInstall(JNIEnv* env, jclass /*clazz*/, jstring path) {
    if (path == nullptr) return JNI_FALSE;

    const char* utf = env->GetStringUTFChars(path, nullptr);
    if (utf == nullptr) return JNI_FALSE;

    if (g_fd < 0) {
        g_fd = open(utf, O_WRONLY | O_APPEND | O_CREAT, 0644);
    }
    env->ReleaseStringUTFChars(path, utf);
    if (g_fd < 0) return JNI_FALSE;

    struct sigaction action;
    memset(&action, 0, sizeof(action));
    action.sa_sigaction = handler;
    action.sa_flags = SA_SIGINFO | SA_ONSTACK;
    sigemptyset(&action.sa_mask);

    const int signals[] = { SIGSEGV, SIGABRT, SIGILL, SIGBUS, SIGFPE };
    bool ok = true;
    for (int s : signals) {
        if (sigaction(s, &action, nullptr) != 0) ok = false;
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_vela_app_ReasonSignal_nativeCrash(JNIEnv* /*env*/, jclass /*clazz*/) {
    // Deliberate null write, for the self-test. The handler above records it and
    // the process dies, which is the point.
    volatile int* address = reinterpret_cast<volatile int*>(0);
    *address = 1;
}
