cmake_minimum_required(VERSION 3.22)
project(vela LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# --- reason signal handler -------------------------------------------------
add_library(reason_signal SHARED reason_signal.cpp)
set_target_properties(reason_signal PROPERTIES CXX_STANDARD 17 CXX_STANDARD_REQUIRED ON)

# --- Preloader SDK (mod menu ABI) -------------------------------------------
# Mod menu ABI: LiteLDev (preloader-android), used with permission and credited
# in About, README and the release notes.
#
# The SDK is vendored at preloader-sdk/ and is NOT yet in this build. It needs
# two things resolved first, and shipping a half-wired native target would put a
# red build in the way of everything else:
#
#   1. its CMake fetches nlohmann_json and boost_pfr at configure time
#   2. it reaches the host Activity through
#      Java_org_levimc_launcher_preloader_PreloaderInput_nativeSetActivity,
#      a package name that is not ours
#
# Until both are settled, vela_modmenu is not built and ModMenuBridge reports
# "not ready" rather than pretending a tap did something.
