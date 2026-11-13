cmake_minimum_required(VERSION 3.22)
project(vela LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(reason_signal SHARED reason_signal.cpp)
set_target_properties(reason_signal PROPERTIES CXX_STANDARD 17 CXX_STANDARD_REQUIRED ON)

# Mod menu ABI: LiteLDev (preloader-android), used with permission.
# Their CMakeLists is used unmodified; its FetchContent pulls nlohmann_json and
# boost_pfr at configure time, which CI has network for.
add_subdirectory(preloader-sdk preloader-build)

add_library(vela_modmenu SHARED modmenu_bridge.cpp)
target_include_directories(vela_modmenu PRIVATE preloader-sdk/include)
target_link_libraries(vela_modmenu PRIVATE preloader dl)
