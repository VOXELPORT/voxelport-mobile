// Pulls in libc++_shared.so so it ships in the APK: the bundled Java runtime
// (libjvm.so, libjli.so, ...) lists it as a dependency.
#include <string>

extern "C" int vp_stl_anchor() { return static_cast<int>(std::string("voxelport").size()); }
