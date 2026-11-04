#pragma once
#include "depth_buffer.h"
#include "include/core/SkCanvas.h"
#include "include/core/SkImage.h"
#include "include/core/SkSamplingOptions.h"
#include "include/core/SkM44.h"
#include <functional>
#include <map>

namespace am {
using DepthCapture=std::function<bool(int,int,const std::function<void(SkCanvas*)>&,std::vector<uint8_t>*)>;
struct DepthContext {
    depth::Buffer& buffer;
    SkM44 device;
    DepthCapture capture;
    std::map<uint32_t,depth::Texture> textures;
    const depth::Texture* texture(const sk_sp<SkImage>& image) {
        if(!image) return nullptr;
        auto [at,fresh]=textures.try_emplace(image->uniqueID());
        if(fresh) {
            auto& t=at->second;
            const float ratio=std::min(1.f,2048.f/std::max(image->width(),image->height()));
            t.width=std::max(1,(int)std::lround(image->width()*ratio));t.height=std::max(1,(int)std::lround(image->height()*ratio));
            if(!capture(t.width,t.height,[&](SkCanvas* c){c->drawImageRect(image,SkRect::MakeWH(t.width,t.height),SkSamplingOptions(SkFilterMode::kLinear));},&t.rgba)) {
                t.width=t.height=0;
            }
        }
        return &at->second;
    }
};
}
