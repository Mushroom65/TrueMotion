#pragma once

#include "scene.h"
#include "true_particles_math.h"

namespace am {
struct TrueParticleState { trueparticles::System system; };
bool TrueParticleEffect(const std::string& id);
bool TrueHasParticles(const Layer& layer);
bool TrueHasWorldParticles(const Layer& layer, double timeMs);
SkRect TrueParticleBounds(const Layer& layer, double timeMs);
SkRect TrueParticleSceneBounds(const Layer& layer, double timeMs, const SkMatrix& localToScene);
SkRect TrueParticleViewBounds(const Scene& scene, const Layer& layer, double timeMs);
using TrueParticleTexture = std::function<sk_sp<SkImage>(const LayerEffect&)>;
void TrueDrawParticles(SkCanvas* canvas, const Layer& layer, double timeMs, float alpha, const SkMatrix& localToScene, const TrueParticleTexture& texture);
}
