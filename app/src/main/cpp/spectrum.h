#pragma once
#include "scene.h"
namespace am {
bool SpectrumEffect(const std::string& id);
bool HasSpectrum(const Layer& layer);
SkRect SpectrumBounds(const Layer& layer,double timeMs);
void DrawSpectrum(SkCanvas* canvas,const Layer& layer,double timeMs,float alpha);
}
