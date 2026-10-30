#include "../../app/src/main/cpp/particles_math.h"
#include <cassert>
#include <cstdio>
#include <chrono>

using namespace am::particles;
bool near(double a,double b) { return std::abs(a-b)<1e-8; }
bool equal(const std::vector<Particle>& a,const std::vector<Particle>& b) {
    if(a.size()!=b.size()) return false;
    for(size_t i=0;i<a.size();i++) {
        if(!near(a[i].position.x,b[i].position.x)||!near(a[i].position.y,b[i].position.y)||!near(a[i].position.z,b[i].position.z)) return false;
        if(!near(a[i].radius,b[i].radius)||!near(a[i].rotation,b[i].rotation)||a[i].color!=b[i].color) return false;
    }
    return true;
}
int main() {
    Config c; c.count=1; c.life=10; c.emitter=0; c.spread=0; c.velocity={2,-1,3}; c.gravity={1,2,-3}; c.softFade=false;
    System exact;
    auto p=exact.sample(c,2).front();
    assert(near(p.position.x,6)&&near(p.position.y,2)&&near(p.position.z,0));
    c.gravity={}; c.drag=2;
    p=exact.sample(c,1).front();
    assert(near(p.position.x,1-std::exp(-2)));
    c.drag=0; c.speedStart=0; c.speedEnd=2;
    p=exact.sample(c,2).front();
    assert(near(p.position.x,.8));
    c.count=64; c.seed=UINT64_C(9223372036854775807); c.life=3.7; c.lifeRandom=.6; c.velocityRandom=.8;
    c.spread=.6; c.drag=.5; c.turbulence=.8; c.attractStrength=.3; c.attractor={1,1,1}; c.rotationSpeed=120;
    System shuffled, chronological;
    const auto expected=chronological.sample(c,183.123);
    for(double t:{0.,.5,1.,2.,181.,183.,183.123,199.,.001,183.123}) shuffled.sample(c,t);
    assert(equal(expected,shuffled.sample(c,183.123)));
    auto steps=shuffled.integrationSteps;
    assert(equal(expected,shuffled.sample(c,183.123))&&steps==shuffled.integrationSteps);
    // Changing appearance does not discard the expensive physics grid states.
    c.tint={.2,.4,.9,1}; c.radius=.1;
    shuffled.sample(c,183.123);
    assert(shuffled.integrationSteps-steps<=64);
    for(int emitter=0;emitter<4;++emitter) {
        Config spawn; spawn.count=128; spawn.emitter=emitter; spawn.life=100; spawn.timeOffset=100;
        spawn.velocity={}; spawn.spread=0; spawn.gravity={}; spawn.spawn={2,3,4};
        System s;
        const auto& all=s.sample(spawn,0);
        for(const auto& one:all) {
            const Vec p=one.position;
            if(emitter==0) assert(p.length()==0);
            if(emitter==1) assert(p.x*p.x/4+p.y*p.y/9+p.z*p.z/16<=1.0000001);
            if(emitter==2) assert(std::abs(p.x)<=2&&std::abs(p.y)<=3&&std::abs(p.z)<=4);
            if(emitter==3) assert(p.x*p.x/4+p.y*p.y/9<=1.0000001&&p.z==0);
        }
    }
    Config pull; pull.count=1; pull.emitter=0; pull.origin={2,0,0}; pull.velocity={}; pull.gravity={}; pull.spread=0; pull.attractStrength=1;
    System attract,repel;
    const double attracted=attract.sample(pull,1).front().position.x;
    pull.attractStrength=-1;
    assert(attracted<2&&repel.sample(pull,1).front().position.x>2);
    Config cone; cone.count=128; cone.emitter=0; cone.velocity={0,-1,0}; cone.spread=1; cone.directionSpread=0; cone.gravity={};
    System axis;
    for(const auto& a:axis.sample(cone,2)) assert(near(a.velocity.x,0)&&near(a.velocity.z,0)&&a.velocity.y<0);
    Vec v{.2,-.7,1.3}, angles{21,42,67};
    Vec undo=InverseRotate(Rotate(v,angles),angles);
    assert((undo-v).length()<1e-12);
    Config perf; perf.count=256; perf.turbulence=.4; perf.attractStrength=.15;
    System fast;
    auto start=std::chrono::steady_clock::now();
    for(int f=0;f<300;++f) fast.sample(perf,f/60.0);
    const double ms=std::chrono::duration<double,std::milli>(std::chrono::steady_clock::now()-start).count();
    std::printf("{\"ok\":true,\"checks\":11,\"largeSeedAnd183Seconds\":true,\"frameOrderIndependent\":true,\"simulation300FramesMs\":%.3f,\"steps\":%llu}\n",ms,(unsigned long long)fast.integrationSteps);
}
