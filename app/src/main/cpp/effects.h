#pragma once

#include "scene.h"
#include "include/effects/SkRuntimeEffect.h"
#include <functional>
#include <map>
#include <string>
#include <vector>

class SkImageFilter;

namespace am {

class EffectRuntime {
public:
    std::string load(const std::string& xml);
    bool active(const Layer& layer) const;
    bool lifted(const Layer& layer) const;
    bool behind(const Layer& layer) const;
    bool draw(SkCanvas* canvas, const Scene& scene, const Layer& layer, double timeMs, const SkRect& area, const SkMatrix& placed,
              const std::function<void(SkCanvas*)>& source, const std::function<void(SkCanvas*)>& below) const;

private:
    struct Stage {
        std::string source;
        sk_sp<SkRuntimeEffect> shader;
        std::string target;
        float downsample = 1;
    };
    struct Definition {
        sk_sp<SkRuntimeEffect> shader;
        bool blurred = false;
        bool brightPass = false;
        bool backdrop = false;
        bool below = false;
        bool pointwise = false;
        std::map<std::string, Anim> defaults;
        bool afterz = false;
        bool pending = false;
        std::string error;
        std::string iterations;
        std::vector<Stage> stages;
        std::map<std::string, std::string> aliases;
        std::map<std::string, std::string> inputs;
    };
    static std::string compile(Definition& definition);
    const Definition* find(const std::string& id) const;
    int liftAt(const Layer& layer) const;
    bool needs(const Layer& layer, bool Definition::*input) const;
    sk_sp<SkImageFilter> chain(const Scene& scene, const Layer& layer, double timeMs, const SkRect& area, const SkRect& seen,
                               const SkMatrix& placed, sk_sp<SkImageFilter> content, sk_sp<SkImageFilter> below) const;
    mutable std::map<std::string, Definition> fDefinitions;
};

}
