#pragma once

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <vector>
#include <tuple>

namespace am::particles {

struct Vec {
    double x = 0, y = 0, z = 0;
    bool operator==(const Vec&) const = default;
    Vec operator+(Vec b) const { return {x + b.x, y + b.y, z + b.z}; }
    Vec operator-(Vec b) const { return {x - b.x, y - b.y, z - b.z}; }
    Vec operator*(double b) const { return {x * b, y * b, z * b}; }
    double length() const { return std::sqrt(x*x + y*y + z*z); }
    Vec unit() const { const double n = length(); return n > 1e-12 ? *this * (1/n) : Vec{0, -1, 0}; }
    Vec cross(Vec b) const { return {y*b.z-z*b.y, z*b.x-x*b.z, x*b.y-y*b.x}; }
};

struct Config {
    int count = 128, emitter = 3;
    uint64_t seed = 1;
    double life = 3, lifeRandom = 0, timeScale = 1, timeOffset = 0;
    Vec origin, spawn{.6, .15, 0}, velocity{0, -.35, 0}, gravity{0, .18, 0}, wind, attractor;
    double spread = .5, directionSpread = 180, velocityRandom = 0, speedStart = 1, speedEnd = 1;
    double forceRandom = 0, drag = 0, turbulence = 0, noiseFrequency = 1;
    double attractStrength = 0, attractRadius = .25;
    double radius = .055, sizeStart = 1, sizeEnd = 0, lifeCurve = 1, variation = .35;
    std::array<double,4> tint{1, .72, .36, 1}, colorStart{1,1,1,1}, colorEnd{1,1,1,1}; // RGBA
    double colorRandom = 0, opacityStart = 1, opacityEnd = 0, opacityRandom = 0;
    bool softFade = true, alignVelocity = false;
    double rotation = 0, rotationRandom = 0, rotationSpeed = 0, spinRandom = 0;
    bool operator==(const Config&) const = default;
    bool physicsEqual(const Config& b) const {
        return std::tie(count,emitter,seed,life,lifeRandom,origin,spawn,velocity,gravity,wind,attractor,spread,directionSpread,velocityRandom,speedStart,speedEnd,forceRandom,drag,turbulence,noiseFrequency,attractStrength,attractRadius) ==
               std::tie(b.count,b.emitter,b.seed,b.life,b.lifeRandom,b.origin,b.spawn,b.velocity,b.gravity,b.wind,b.attractor,b.spread,b.directionSpread,b.velocityRandom,b.speedStart,b.speedEnd,b.forceRandom,b.drag,b.turbulence,b.noiseFrequency,b.attractStrength,b.attractRadius);
    }
};

struct Particle {
    Vec position, velocity;
    double radius = 0, rotation = 0;
    std::array<double,4> color{};
};

inline uint64_t Mix(uint64_t n) {
    n = (n ^ (n >> 30)) * UINT64_C(0xbf58476d1ce4e5b9);
    n = (n ^ (n >> 27)) * UINT64_C(0x94d049bb133111eb);
    return n ^ (n >> 31);
}
inline double Random(uint64_t seed, uint64_t index, uint64_t cycle, uint64_t salt) {
    return (Mix(seed ^ Mix(index + 0x9e3779b9ULL) ^ Mix(cycle) ^ Mix(salt + 0x85ebca6bULL)) >> 11) * (1.0 / 9007199254740992.0);
}
inline double Smooth(double a, double b, double x) {
    const double t = std::clamp((x-a)/(b-a), 0.0, 1.0);
    return t*t*(3-2*t);
}

/** Fixed 60 Hz integration, with only the final fractional step sampled temporarily.
 * Cached grid states never depend on preview FPS or the order of exported frames. */
class System {
    struct Slot {
        uint64_t cycle = UINT64_MAX;
        int steps = 0;
        Vec initial, start, p, v, phase;
        double force = 1, lifetime = 1;
    };
    Config config;
    bool configured = false;
    double sampled = NAN;
    std::vector<Slot> slots;
    std::vector<Particle> output;
    static constexpr double dt = 1.0 / 60;

    Slot birth(int index, uint64_t cycle, double life) const {
        constexpr double pi = 3.14159265358979323846;
        auto random = [&](uint64_t salt) { return Random(config.seed, index, cycle, salt); };
        Slot s;
        s.cycle = cycle; s.lifetime = life;
        const double angle = random(1)*2*pi, z = random(2)*2-1;
        Vec direction{std::sqrt(std::max(0.0, 1-z*z))*std::cos(angle), std::sqrt(std::max(0.0, 1-z*z))*std::sin(angle), z};
        Vec spawn;
        if (config.emitter == 1) {
            const Vec p = direction * std::cbrt(random(3));
            spawn = {p.x*config.spawn.x, p.y*config.spawn.y, p.z*config.spawn.z};
        } else if (config.emitter == 2) {
            spawn = {(random(4)*2-1)*config.spawn.x, (random(5)*2-1)*config.spawn.y, (random(6)*2-1)*config.spawn.z};
        } else if (config.emitter == 3) {
            const double r = std::sqrt(random(3));
            spawn = {std::cos(angle)*r*config.spawn.x, std::sin(angle)*r*config.spawn.y, 0};
        }
        if (config.directionSpread < 179.999) {
            const Vec axis = config.velocity.unit();
            const Vec tangent = axis.cross(std::abs(axis.z) < .9 ? Vec{0,0,1} : Vec{0,1,0}).unit();
            const double ca = 1 + (std::cos(config.directionSpread*pi/180)-1)*random(7);
            direction = axis*ca + (tangent*std::cos(angle) + axis.cross(tangent)*std::sin(angle))*std::sqrt(std::max(0.0, 1-ca*ca));
        }
        s.initial = (config.velocity + direction*config.spread) * (1 + config.velocityRandom*(random(8)*2-1));
        s.start = s.p = config.origin + spawn;
        s.v = s.initial * config.speedStart;
        s.force = 1 + config.forceRandom*(random(9)*2-1);
        s.phase = {random(10)*2*pi, random(11)*2*pi, random(12)*2*pi};
        return s;
    }
    void advance(Slot& s, double at, double step) {
        Vec a = (config.gravity + config.wind)*s.force + s.initial*((config.speedEnd-config.speedStart)/s.lifetime);
        if (config.turbulence > 0) {
            const double f = config.noiseFrequency;
            a = a + Vec{std::sin(s.phase.x+s.p.y*f+at*f), std::sin(s.phase.y+s.p.z*f+at*f), std::sin(s.phase.z+s.p.x*f+at*f)}*config.turbulence;
        }
        if (config.attractStrength != 0) {
            const Vec delta = config.attractor-s.p;
            const double distance = std::max(config.attractRadius, delta.length());
            Vec pull = delta * (config.attractStrength/(distance*distance*distance));
            const double magnitude = pull.length();
            if (magnitude > 50) pull = pull * (50/magnitude);
            a = a + pull;
        }
        const double k = config.drag;
        const double travel = k > 1e-6 ? -std::expm1(-k*step)/k : step;
        const double forceTime = k > 1e-6 ? (step-travel)/k : step*step/2;
        s.p = s.p + s.v*travel + a*forceTime;
        s.v = s.v*std::exp(-k*step) + a*travel;
        ++integrationSteps;
    }
public:
    uint64_t integrationSteps = 0;
    const std::vector<Particle>& sample(const Config& next, double seconds) {
        const bool same=configured && next==config;
        if (!configured || !next.physicsEqual(config)) {
            sampled = NAN;
            slots.assign(std::clamp(next.count, 1, 4096), Slot{});
        }
        config=next; configured=true;
        const double time = seconds*config.timeScale + config.timeOffset;
        if (same && time == sampled) return output;
        sampled = time; output.clear(); output.reserve(slots.size());
        if (!std::isfinite(time) || time < 0 || time > 1e12) return output;
        for (size_t i = 0; i < slots.size(); ++i) {
            const double life = std::max(.05, config.life*(1-config.lifeRandom*Random(config.seed, i, 0, 97)));
            const double since = time - i*life/slots.size();
            if (since < 0) continue;
            const auto cycle = (uint64_t)std::floor(since/life);
            const double age = std::clamp(since-cycle*life, 0.0, life);
            const int steps = (int)std::floor(age/dt + 1e-9);
            Slot& cached = slots[i];
            if (cached.cycle != cycle || cached.steps > steps) cached = birth((int)i, cycle, life);
            while (cached.steps < steps) { advance(cached, cached.steps*dt, dt); ++cached.steps; }
            Slot drawn = cached;
            if (age > steps*dt + 1e-10) advance(drawn, steps*dt, age-steps*dt);
            const double u = age/life, lifeMix = std::pow(u, config.lifeCurve);
            auto random = [&](uint64_t salt) { return Random(config.seed, i, cycle, salt); };
            Particle p;
            p.position = drawn.p; p.velocity = drawn.v;
            p.radius = config.radius*(config.sizeStart+(config.sizeEnd-config.sizeStart)*lifeMix)*(1-config.variation*random(71));
            p.rotation = config.rotation + config.rotationRandom*(random(72)*2-1) + config.rotationSpeed*(1+config.spinRandom*(random(73)*2-1))*age;
            double alpha = (config.opacityStart+(config.opacityEnd-config.opacityStart)*lifeMix)*(1-config.opacityRandom*random(74));
            if (config.softFade) alpha *= Smooth(0, .08, u)*(1-Smooth(.65, 1, u));
            for (int c = 0; c < 4; ++c) p.color[c] = config.tint[c]*(config.colorStart[c]+(config.colorEnd[c]-config.colorStart[c])*lifeMix);
            for (int c = 0; c < 3; ++c) p.color[c] *= 1-config.colorRandom*random(80+c);
            p.color[3] *= std::clamp(alpha, 0.0, 1.0);
            output.push_back(p);
        }
        return output;
    }
};

inline Vec Rotate(Vec p, Vec degrees) {
    const double f = 3.14159265358979323846/180;
    Vec a = degrees*f;
    p = {p.x, std::cos(a.x)*p.y-std::sin(a.x)*p.z, std::sin(a.x)*p.y+std::cos(a.x)*p.z};
    p = {std::cos(a.y)*p.x+std::sin(a.y)*p.z, p.y, -std::sin(a.y)*p.x+std::cos(a.y)*p.z};
    return {std::cos(a.z)*p.x-std::sin(a.z)*p.y, std::sin(a.z)*p.x+std::cos(a.z)*p.y, p.z};
}
// The inverse must reverse the rotation order, rather than negate XYZ in place.
inline Vec InverseRotate(Vec p, Vec degrees) {
    p = Rotate(p, {0,0,-degrees.z});
    p = Rotate(p, {0,-degrees.y,0});
    return Rotate(p, {-degrees.x,0,0});
}

} // namespace am::particles
