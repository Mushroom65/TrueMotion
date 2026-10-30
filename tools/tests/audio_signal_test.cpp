#include "../../app/src/main/cpp/audio_signal.h"
#include <cassert>
#include <limits>

int main() {
    am::AudioSignal signal{1000, 10, 0, 100, {0, 1, .5f}};
    assert(signal.at(999) == 0);
    assert(signal.at(1005) == 0);
    assert(signal.at(1010) == .5f);
    assert(signal.at(1015) == 1);
    assert(signal.at(1020) == .75f);
    assert(signal.at(1030) == .5f);
    assert(std::abs(signal.at(1130) - .5f / std::exp(1.0)) < 1e-6);
    signal.silence = 1;
    assert(signal.at(0) == 1);
    assert(signal.at(100000) == 1);
    assert(signal.at(std::numeric_limits<double>::quiet_NaN()) == 1);
    signal.levels.clear();
    assert(signal.at(1015) == 1);
}
