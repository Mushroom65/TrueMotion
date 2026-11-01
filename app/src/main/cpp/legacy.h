#pragma once
#include "scene.h"
#include <functional>

namespace am {
using LegacyCallback = std::function<std::string(const std::string&,const std::string&,const std::string&)>;
void SetLegacyCallback(LegacyCallback callback);
void RegisterLegacy(const std::string& id,const XmlNode& definition);
bool LegacyScript(const std::string& id);
bool LegacyTextEffect(const Layer& layer);
void ApplyLegacy(const Layer& layer,const LayerEffect& effect,double timeMs,Placement* placement);
float LegacyOpacity(const Layer& layer,double timeMs);
std::string LegacyText(const Layer& layer,float t,const std::string& content);
std::vector<int> LegacyGroups(const Layer& layer,const LayerEffect& effect,double timeMs);
std::string TextContent(const Layer& layer);
}
