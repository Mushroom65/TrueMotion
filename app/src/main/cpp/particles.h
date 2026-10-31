#pragma once

#include "scene.h"
#include "particles_math.h"

namespace am {
struct ParticleState { particles::System system; };
bool ParticleEffect(const std::string& id);
bool HasParticles(const Layer& layer);
bool HasWorldParticles(const Layer& layer, double timeMs);
SkRect ParticleBounds(const Layer& layer, double timeMs);
SkRect ParticleSceneBounds(const Layer& layer, double timeMs, const SkMatrix& localToScene);
SkRect ParticleViewBounds(const Scene& scene, const Layer& layer, double timeMs);
using ParticleTexture = std::function<sk_sp<SkImage>(const LayerEffect&)>;
void DrawParticles(SkCanvas* canvas, const Layer& layer, double timeMs, float alpha, const SkMatrix& localToScene, const ParticleTexture& texture);
}
