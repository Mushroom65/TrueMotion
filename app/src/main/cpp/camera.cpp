#include <algorithm>
#include <cmath>
#include <numeric>

#include "include/core/SkMatrix.h"
#include "scene.h"
#include "true_particles.h"

namespace am {
namespace {

constexpr const char* kFocusBlurId = "absent.blur";
constexpr const char* kFocusBlurStrength = "strength";
constexpr const char* kFogId = "absent.solid";
constexpr const char* kFogColor = "color";
constexpr const char* kFogAmount = "amount";
constexpr const char* kMotionBlurId = "absent.motionblur";
constexpr const char* kMotionBlurTune = "tune";
constexpr const char* kSmearId = "absent.smear";
constexpr const char* kSmearAngle = "angle";
constexpr const char* kSmearLength = "length";

constexpr float kDefaultFov = 60;
constexpr float kNearest = 1;
constexpr float kFocusBlurPerDepth = 0.1f;

thread_local bool gCameraView = true;

float FocalLength(int width, int height, float fov) {
    const float half = std::clamp(fov, 0.1f, 179.9f) * 0.5f * 3.14159265f / 180;
    return std::max(width, height) / (2 * tanf(half));
}

struct View {
    SkPoint eye, middle;
    float eyeZ = 0, rotation = 0, focal = 1;
    bool orthographic = false;
    bool tilted = false;
    SkM44 turn;
};

View DefaultView(int width, int height) {
    View v;
    v.middle = v.eye = {width / 2.0f, height / 2.0f};
    v.focal = FocalLength(width, height, kDefaultFov);
    v.eyeZ = -v.focal;
    return v;
}

View LayerView(const std::vector<Layer>& layers, const Layer& camera, int width, int height, double timeMs) {
    const Placement p = EffectivePlacement(layers, camera, timeMs);
    View v;
    v.middle = {width / 2.0f, height / 2.0f};
    v.eye = p.location;
    v.eyeZ = p.z;
    v.rotation = p.rotation;
    v.tilted = p.tilt != SkM44();
    const SkM44 orientation = SkM44::Rotate({0, 0, 1}, p.rotation * 3.14159265f / 180) * p.tilt;
    if (!orientation.invert(&v.turn)) v.turn = SkM44();
    v.focal = FocalLength(width, height, camera.camera.fov.get(0, camera.localTime(timeMs), kDefaultFov));
    v.orthographic = camera.camera.orthographic;
    return v;
}


struct Seen {
    SkPoint location;
    SkVector scale;
    float rotation, depth;
    bool visible;
};

Seen Look(const View& v, const Placement& p) {
    Seen s;
    const SkV4 viewed = v.turn.map(p.location.fX - v.eye.fX, p.location.fY - v.eye.fY, p.z - v.eyeZ, 0);
    s.depth = v.tilted ? viewed.z : p.z - v.eyeZ;
    s.visible = s.depth >= kNearest;
    const float k = v.focal / (v.orthographic ? std::max(-v.eyeZ, kNearest) : std::max(s.depth, kNearest));
    s.location = v.middle + (v.tilted ? SkVector{viewed.x * k, viewed.y * k} : SkMatrix::RotateDeg(-v.rotation).mapVector((p.location - v.eye) * k));
    s.scale = {p.scale.fX * k, p.scale.fY * k};
    s.rotation = p.rotation - v.rotation;
    return s;
}

SkM44 Space(const View& v, const Placement& p) {
    const float r = 3.14159265f / 180, mx = v.middle.fX, my = v.middle.fY, f = v.focal;
    const SkM44 world = p.fullTransform ? p.model : SkM44::Translate(p.location.fX, p.location.fY, p.z) * SkM44::Rotate({0, 0, 1}, p.rotation * r) * p.tilt;
    const SkM44 eye = v.turn * SkM44::Translate(-v.eye.fX, -v.eye.fY, -v.eyeZ);
    const float c = std::max(-v.eyeZ, kNearest);
    const SkM44 lens = v.orthographic ? SkM44(f, 0, 0, mx * c, 0, f, 0, my * c, 0, 0, 1, 0, 0, 0, 0, c)
                                      : SkM44(f, 0, mx, 0, 0, f, my, 0, 0, 0, 1, 0, 0, 0, 1, 0);
    return lens * eye * world;
}

bool Solid(const Layer& l, const Placement& p, double timeMs) { return p.tilt != SkM44() || l.kind == "model" || TrueHasWorldParticles(l,timeMs); }

LayerEffect Effect(const char* id, std::vector<std::pair<const char*, std::vector<float>>> values) {
    LayerEffect e;
    e.id = id;
    for (auto& [name, value] : values) {
        Property p;
        p.name = name;
        p.type = value.size() == 4 ? "color" : "float";
        p.anim.value = std::move(value);
        e.props.push_back(std::move(p));
    }
    return e;
}

float MotionBlurTune(const Layer& camera, float t) {
    for (const LayerEffect& e : camera.effects) {
        if (e.hidden || e.id != kMotionBlurId) continue;
        for (const Property& p : e.props) {
            if (p.name == kMotionBlurTune) return std::clamp(p.anim.get(0, t, 1), 0.0f, 4.0f);
        }
        return 1;
    }
    return 0;
}

void AddCameraEffects(const Layer& camera, double timeMs, float depth, SkPoint before, SkPoint now, Layer* seen) {
    const Camera& c = camera.camera;
    const float t = camera.localTime(timeMs);
    if (c.focusBlur) {
        const float strength = c.focusStrength.get(0, t, 0.5f), band = c.focusDepth.get(0, t, 100) / 2;
        const float outside = std::fabs(depth - c.focusDistance.get(0, t, 500)) - band;
        const float radius = strength * outside * kFocusBlurPerDepth;
        if (radius > 0) seen->effects.push_back(Effect(kFocusBlurId, {{kFocusBlurStrength, {radius}}}));
    }
    if (c.fog) {
        const float nearZ = c.fogNear.get(0, t, 0), farZ = c.fogFar.get(0, t, 500);
        const float amount = farZ > nearZ ? std::clamp((depth - nearZ) / (farZ - nearZ), 0.0f, 1.0f) : depth >= nearZ ? 1 : 0;
        if (amount > 0) {
            std::vector<float> color = {c.fogColor.get(0, t, 1), c.fogColor.get(1, t, 1), c.fogColor.get(2, t, 1), c.fogColor.get(3, t, 1)};
            seen->effects.push_back(Effect(kFogId, {{kFogColor, color}, {kFogAmount, {amount}}}));
        }
    }
    if (const float tune = MotionBlurTune(camera, t); tune > 0) {
        const SkVector moved = now - before;
        const float length = moved.length() * tune;
        if (length > 0) {
            const float angle = atan2f(moved.fY, moved.fX) * 180 / 3.14159265f;
            seen->effects.push_back(Effect(kSmearId, {{kSmearAngle, {angle}}, {kSmearLength, {length}}}));
        }
    }
}

}

SkMatrix PlaneMatrix(const std::vector<Layer>& layers, const Layer& layer, int width, int height, double timeMs) {
    const Layer* camera = gCameraView ? ActiveCamera(layers, timeMs) : nullptr;
    const View v = camera ? LayerView(layers, *camera, width, height, timeMs) : DefaultView(width, height);
    if (v.tilted) {
        Placement plane{};
        plane.z = EffectivePlacement(layers, layer, timeMs).z;
        const SkM44 matrix = Space(v, plane);
        SkMatrix result;
        result.setAll(matrix.rc(0, 0), matrix.rc(0, 1), matrix.rc(0, 3),
                      matrix.rc(1, 0), matrix.rc(1, 1), matrix.rc(1, 3),
                      matrix.rc(3, 0), matrix.rc(3, 1), matrix.rc(3, 3));
        return result;
    }
    const float depth = EffectivePlacement(layers, layer, timeMs).z - v.eyeZ;
    const float k = v.focal / (v.orthographic ? std::max(-v.eyeZ, kNearest) : std::max(depth, kNearest));
    return SkMatrix::Translate(v.middle.fX, v.middle.fY).preRotate(-v.rotation).preScale(k, k).preTranslate(-v.eye.fX, -v.eye.fY);
}

void SetCameraView(bool on) { gCameraView = on; }

bool CameraViewOn() { return gCameraView; }

bool CameraFrame(const std::vector<Layer>& layers, const Layer& camera, int width, int height, double timeMs, SkPoint corners[4]) {
    if (width <= 0 || height <= 0) return false;
    const Placement p = EffectivePlacement(layers, camera, timeMs);
    if (p.tilt != SkM44()) {
        const View view = LayerView(layers, camera, width, height, timeMs);
        SkM44 orientation;
        if (!view.turn.invert(&orientation)) return false;
        const float xs[] = {0, (float)width, (float)width, 0};
        const float ys[] = {0, 0, (float)height, (float)height};
        const float c = view.orthographic ? std::max(-p.z, kNearest) : 1;
        for (int i = 0; i < 4; i++) {
            const SkV4 offset = view.orthographic ? orientation.map((xs[i] - view.middle.fX) * c / view.focal, (ys[i] - view.middle.fY) * c / view.focal, 0, 0) : SkV4{0, 0, 0, 0};
            const SkV4 ray = orientation.map(view.orthographic ? 0 : (xs[i] - view.middle.fX) / view.focal,
                                             view.orthographic ? 0 : (ys[i] - view.middle.fY) / view.focal, 1, 0);
            if (std::fabs(ray.z) < 1e-5f) return false;
            const float distance = -(p.z + offset.z) / ray.z;
            const float x = p.location.fX + offset.x + ray.x * distance;
            const float y = p.location.fY + offset.y + ray.y * distance;
            if (!(distance > 0) || !std::isfinite(x) || !std::isfinite(y)) return false;
            corners[i] = {x, y};
        }
        return true;
    }
    const float distance = camera.camera.orthographic ? std::max(-p.z, kNearest) : -p.z;
    if (!(distance > 0)) return false;
    const float reach = distance / FocalLength(width, height, camera.camera.fov.get(0, camera.localTime(timeMs), kDefaultFov));
    const float w = width * 0.5f * reach, h = height * 0.5f * reach;
    SkRect::MakeLTRB(-w, -h, w, h).toQuad(corners);
    SkMatrix::Translate(p.location.fX, p.location.fY).preRotate(p.rotation).mapPoints({corners, 4});
    return true;
}

bool SeenPlacement(const Layer& l, double timeMs, Placement* out) {
    const Sight& s = l.sight;
    if (!s.layers) return false;
    Placement p = EffectivePlacement(*s.layers, *s.layer, timeMs);
    if (l.kind != "camera") {
        const View v = s.camera ? LayerView(*s.layers, *s.camera, s.width, s.height, timeMs) : DefaultView(s.width, s.height);
        if (l.projected) {
            p.space = Space(v, p);
            p.turn = v.tilted ? v.turn * SkM44::Rotate({0, 0, 1}, p.rotation * 3.14159265f / 180) * p.tilt
                              : SkM44::Rotate({0, 0, 1}, (p.rotation - v.rotation) * 3.14159265f / 180) * p.tilt;
        } else {
            const Seen seen = Look(v, p);
            if(p.fullTransform) p.model=SkM44(PlaneMatrix(*s.layers,*s.layer,s.width,s.height,timeMs))*p.model;
            p.location = seen.location;
            p.scale = seen.scale;
            p.rotation = seen.rotation;
            p.z = 0;
        }
    }
    *out = p;
    return true;
}

bool SeeLayers(const std::vector<Layer>& layers, int width, int height, bool top, int fps, double timeMs, std::vector<Layer>* seen) {
    const Layer* camera = top && gCameraView ? ActiveCamera(layers, timeMs) : nullptr;
    if (!camera) {
        bool flat = true;
        for (const Layer& l : layers) {
            const Placement p = EffectivePlacement(layers, l, timeMs);
            flat = flat && p.z == 0 && !Solid(l, p, timeMs);
        }
        if (flat) return false;
    }
    const double beforeMs = timeMs - 1000.0 / std::max(fps, 1);
    const View now = camera ? LayerView(layers, *camera, width, height, timeMs) : DefaultView(width, height);
    const View before = camera ? LayerView(layers, *camera, width, height, beforeMs) : now;

    std::vector<Layer> copies;
    std::vector<float> depth;
    std::vector<bool> dropped;
    copies.reserve(layers.size());
    for (const Layer& l : layers) {
        const Placement p = EffectivePlacement(layers, l, timeMs);
        Layer copy = l;
        copy.parent = 0;
        copy.placed = true;
        copy.sight = {&layers, &l, camera, width, height};
        float d = 0;
        bool drop = false;
        if (l.kind != "camera") {
            const Seen s = Look(now, p);
            d = s.depth;
            drop = !s.visible && !TrueHasWorldParticles(l,timeMs);
            copy.projected = Solid(l, p, timeMs) || now.tilted;
            if (camera) AddCameraEffects(*camera, timeMs, d, Look(before, EffectivePlacement(layers, l, beforeMs)).location, s.location, &copy);
        }
        copies.push_back(std::move(copy));
        depth.push_back(d);
        dropped.push_back(drop);
    }

    std::vector<size_t> starts;
    for (size_t i = 0; i < copies.size(); i++) {
        if (i == 0 || !copies[i].clippingMask) starts.push_back(i);
    }
    std::vector<size_t> order(starts.size());
    std::iota(order.begin(), order.end(), 0);
    std::stable_sort(order.begin(), order.end(), [&](size_t a, size_t b) { return depth[starts[a]] > depth[starts[b]]; });
    seen->clear();
    for (size_t k : order) {
        const size_t first = starts[k], end = k + 1 < starts.size() ? starts[k + 1] : copies.size();
        if (dropped[first]) continue;
        for (size_t i = first; i < end; i++) {
            if (!dropped[i]) seen->push_back(std::move(copies[i]));
        }
    }
    return true;
}

}
