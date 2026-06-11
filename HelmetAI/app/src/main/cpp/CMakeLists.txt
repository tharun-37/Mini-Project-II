cmake_minimum_required(VERSION 3.22.1)

project("helmetai")

# If OPENCV_ANDROID is set, use it.
if(NOT DEFINED ENV{OPENCV_ANDROID})
    message(STATUS "OPENCV_ANDROID environment variable not set. C++ build may fail unless OpenCV is in system path.")
else()
    set(OpenCV_DIR $ENV{OPENCV_ANDROID}/sdk/native/jni)
endif()

find_package(OpenCV REQUIRED)

add_library( # Sets the name of the library.
        native-lib

        # Sets the library as a shared library.
        SHARED

        # Provides a relative path to your source file(s).
        opencv-utils.cpp
        native-lib.cpp)

find_library( # Sets the name of the path variable.
        log-lib

        # Specifies the name of the NDK library that
        # you want CMake to locate.
        log)

find_library(jnigraphics-lib jnigraphics)

target_link_libraries( # Specifies the target library.
        native-lib
        ${OpenCV_LIBS}
        ${jnigraphics-lib}
        ${log-lib})
