#include "../../app/src/main/cpp/motion_path_math.h"
#include <cassert>
#include <cstdio>
bool near(float a, float b) { return std::abs(a-b) < .001f; }
int main() {
    using namespace am;
    auto p = SampleMotionPath(0,0,100,0,0,100,0,100,.5f);
    assert(near(p.x,50) && near(p.y,75) && near(p.dy,0));
    p = SampleMotionPath(0,0,100,20,0,0,0,0,.25f);
    assert(near(p.x,25) && near(p.y,5));
    assert(near(MotionPathHeading(0,0,100,0,0,100,0,100,0),90));
    assert(near(MotionPathHeading(0,0,100,0,0,100,0,100,1),-90));
    assert(near(MotionPathHeading(0,0,0,0,0,0,0,0,.5f),0));
    assert(std::isfinite(MotionPathHeading(0,0,100,0,0,0,-20,0,0)));
    for (int i=0;i<=100;i++) {
        const float u=i/100.f;
        auto a=SampleMotionPath(10,20,100,50,20,-50,-20,-10,u);
        auto b=SampleMotionPath(100,50,10,20,-20,-10,20,-50,1-u);
        assert(near(a.x,b.x) && near(a.y,b.y));
    }
    std::puts("motion_path_test: 7 checks passed");
}
