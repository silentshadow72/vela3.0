cmake_minimum_required(VERSION 3.22)
project(reasonsignal CXX)

add_library(reason_signal SHARED reason_signal.cpp)
set_target_properties(reason_signal PROPERTIES CXX_STANDARD 17 CXX_STANDARD_REQUIRED ON)
