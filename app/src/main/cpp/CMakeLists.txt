cmake_minimum_required(VERSION 3.22)
project(vplauncher C CXX)

# stl.cpp links libc++_shared, which the bundled Java runtime needs at load time.
add_library(vplauncher SHARED launcher.c stl.cpp)
target_link_libraries(vplauncher dl log)
