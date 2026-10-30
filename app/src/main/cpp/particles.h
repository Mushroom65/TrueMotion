#pragma once

#include "scene.h"
#include "particles_math.h"

namespace am {
struct ParticleState { particles::System system; };
bool ParticleEffect(const std::string& id);
bool HasParticles(const Layer& layer);
SkRect ParticleBounds(const Layer& layer, double timeMs);
using ParticleTexture = std::function<sk_sp<SkImage>(const LayerEffect&)>;
void DrawParticles(SkCanvas* canvas, const Layer& layer, double timeMs, float alpha, const ParticleTexture& texture);
}
