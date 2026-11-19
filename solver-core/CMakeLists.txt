cmake_minimum_required(VERSION 3.20)
project(autodora_solver LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

option(AUTODORA_BUILD_TESTS "Build standalone C++20 solver unit tests and benchmarks" ON)

add_library(autodora_solver STATIC
    src/board.cpp
    src/simulator.cpp
    src/evaluator.cpp
    src/solver.cpp
)

target_include_directories(autodora_solver PUBLIC
    ${CMAKE_CURRENT_SOURCE_DIR}/include
)

if(MSVC)
    target_compile_options(autodora_solver PRIVATE /W4 /O2)
else()
    target_compile_options(autodora_solver PRIVATE -Wall -Wextra -Wpedantic)
    if(CMAKE_BUILD_TYPE STREQUAL "Release" OR CMAKE_BUILD_TYPE STREQUAL "RelWithDebInfo")
        target_compile_options(autodora_solver PRIVATE -O3)
    endif()
endif()

find_package(Threads REQUIRED)
target_link_libraries(autodora_solver PUBLIC Threads::Threads)

if(AUTODORA_BUILD_TESTS)
    add_executable(autodora_solver_tests
        tests/test_main.cpp
    )
    target_link_libraries(autodora_solver_tests PRIVATE autodora_solver)
    enable_testing()
    add_test(NAME solver_unit_tests COMMAND autodora_solver_tests)
endif()
