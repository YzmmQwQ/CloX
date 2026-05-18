#pragma once
#include <atomic>

namespace BlurPostProcess {

extern std::atomic<float> g_radius;

void applyIfNeeded();

// Call when GL context is destroyed (screen off / background).
// Marks resources invalid without calling glDelete on stale handles.
void onContextLost();

}  // namespace BlurPostProcess
