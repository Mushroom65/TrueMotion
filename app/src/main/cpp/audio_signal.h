#pragma once

#include <algorithm>
#include <cmath>
#include <vector>

namespace am {

// Analysis samples, not animation keys. Immutable and shared by every dependent layer.
struct AudioSignal {
    double startMs = 0, stepMs = 10;
    float silence = 0, releaseMs = 0;
    float peak = 0;
    std::vector<float> levels;

    float at(double timeMs) const {
        if (!std::isfinite(timeMs) || levels.empty() || !(stepMs > 0) || timeMs < startMs) return silence;
        const double end = startMs + levels.size() * stepMs;
        if (timeMs >= end) return releaseMs > 0
            ? silence + (levels.back() - silence) * (float)std::exp(-(timeMs - end) / releaseMs) : silence;
        const double position = std::max(0.0, (timeMs - startMs) / stepMs - 0.5);
        const size_t i = std::min((size_t)position, levels.size() - 1);
        const float next = levels[std::min(i + 1, levels.size() - 1)];
        return levels[i] + (next - levels[i]) * (float)std::clamp(position - i, 0.0, 1.0);
    }
};

}
