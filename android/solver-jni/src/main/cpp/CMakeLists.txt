cmake_minimum_required(VERSION 3.22.1)
project(autodora_jni LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(AUTODORA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
add_subdirectory(../../../../../solver-core ${CMAKE_CURRENT_BINARY_DIR}/solver-core-build)

# Always optimize the C++20 solver core even in Debug APKs for real-time on-device performance
target_compile_options(autodora_solver PRIVATE -O3)

add_library(autodora_jni SHARED
    solver_jni.cpp
)

target_link_libraries(autodora_jni
    PRIVATE
    autodora_solver
    log
)
