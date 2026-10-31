#include "particles.h"

#include <cstdlib>
#include "include/core/SkCanvas.h"
#include "include/core/SkColorFilter.h"
#include "include/core/SkPaint.h"
#include "include/effects/SkGradient.h"

namespace am {
namespace {
using particles::Vec;
using particles::Config;

double Number(const LayerEffect& e, const char* name, float t, double fallback, double low, double high) {
    const double value = e.value(name, t, (float)fallback);
    return std::isfinite(value) ? std::clamp(value, low, high) : fallback;
}
Vec Vector(const LayerEffect& e, const char* name, float t, Vec fallback) {
    const Property* p = e.prop(name);
    if (!p) {
        auto get=[&](const char* axis,double value) { return Number(e,(std::string(name)+axis).c_str(),t,value,-10000,10000); };
        return {get("X",fallback.x),get("Y",fallback.y),get("Z",fallback.z)};
    }
    const auto get = [&](int i, double f) { const double v = p->anim.get(i, t, f); return std::isfinite(v) ? std::clamp(v, -10000.0, 10000.0) : f; };
    return {get(0, fallback.x), get(1, fallback.y), get(2, fallback.z)};
}
std::array<double,4> Color(const LayerEffect& e, const char* name, float t, std::array<double,4> fallback) {
    const Property* p = e.prop(name);
    if (!p) return fallback;
    auto get = [&](int i, double f) { const double v = p->anim.get(i, t, f); return std::isfinite(v) ? std::clamp(v, 0.0, 1.0) : f; };
    return {get(1, fallback[0]), get(2, fallback[1]), get(3, fallback[2]), get(0, fallback[3])};
}
Config Settings(const LayerEffect& e, float t) {
    Config c;
    c.count = (int)Number(e, "amount", t, 128, 1, 4096);
    c.emitter = (int)Number(e, "emitter", t, 3, 0, 3);
    if (const Property* seed = e.prop("seed")) {
        c.seed = seed->anim.keys.empty() ? strtoull(seed->text.c_str(), nullptr, 10) : (uint64_t)Number(e, "seed", t, 1, 0, 2147483647);
    }
    c.life = Number(e,"life",t,3,.05,60); c.lifeRandom = Number(e,"lifeRandom",t,0,0,.95);
    c.timeScale = Number(e,"timeScale",t,1,0,10); c.timeOffset = Number(e,"timeOffset",t,0,-3600,3600);
    c.origin=Vector(e,"origin",t,{}); c.spawn={Number(e,"spawnX",t,.6,0,100),Number(e,"spawnY",t,.15,0,100),Number(e,"spawnZ",t,0,0,100)};
    c.velocity=Vector(e,"velocity",t,{0,-.35,0}); c.wind=Vector(e,"wind",t,{}); c.attractor=Vector(e,"attractor",t,{});
    c.gravity={Number(e,"gravityX",t,0,-100,100),Number(e,"gravity",t,.18,-100,100),Number(e,"gravityZ",t,0,-100,100)};
    c.spread=Number(e,"spread",t,.5,0,100); c.directionSpread=Number(e,"directionSpread",t,180,0,180);
    c.velocityRandom=Number(e,"velocityRandom",t,0,0,1); c.speedStart=Number(e,"speedStart",t,1,0,10); c.speedEnd=Number(e,"speedEnd",t,1,0,10);
    c.forceRandom=Number(e,"forceRandom",t,0,0,1); c.drag=Number(e,"drag",t,0,0,30);
    c.turbulence=Number(e,"turbulence",t,0,0,100); c.noiseFrequency=Number(e,"noiseFrequency",t,1,.01,30);
    c.attractStrength=Number(e,"attractStrength",t,0,-100,100); c.attractRadius=Number(e,"attractRadius",t,.25,.01,100);
    c.radius=Number(e,"radius",t,.055,.001,10); c.sizeStart=Number(e,"sizeStart",t,1,0,10); c.sizeEnd=Number(e,"sizeEnd",t,0,0,10);
    c.lifeCurve=Number(e,"lifeCurve",t,1,.1,5); c.variation=Number(e,"variation",t,.35,0,1);
    c.tint=Color(e,"tint",t,c.tint); c.colorStart=Color(e,"colorStart",t,c.colorStart); c.colorEnd=Color(e,"colorEnd",t,c.colorEnd);
    c.colorRandom=Number(e,"colorRandom",t,0,0,1); c.opacityStart=Number(e,"opacityStart",t,1,0,1); c.opacityEnd=Number(e,"opacityEnd",t,0,0,1);
    c.opacityRandom=Number(e,"opacityRandom",t,0,0,1); c.softFade=Number(e,"softFade",t,1,0,1)>.5;
    c.rotation=Number(e,"rotation",t,0,-36000,36000); c.rotationRandom=Number(e,"rotationRandom",t,0,0,360);
    c.rotationSpeed=Number(e,"rotationSpeed",t,0,-36000,36000); c.spinRandom=Number(e,"spinRandom",t,0,0,1);
    c.alignVelocity=Number(e,"alignVelocity",t,0,0,1)>.5;
    c.twoD=Number(e,"dimension",t,-1,-1,1)==0;
    return c;
}
bool World(const LayerEffect& effect, float t) { return Number(effect,"dimension",t,-1,-1,1)>.5; }
struct Sprite { double x,y,r,angle; const particles::Particle* particle; double depth=0; };
std::vector<Sprite> Sprites(const Layer& layer, const LayerEffect& effect, double timeMs) {
    std::vector<Sprite> out;
    if (!effect.particles || effect.hidden) return out;
    const float t=layer.localTime(timeMs);
    const Config c=Settings(effect,t);
    const Vec camera=Vector(effect,"camera",t,{0,0,-3}), rotation=Vector(effect,"camRotation",t,{});
    const double focal=Number(effect,"worldScale",t,300,1,100000)/std::tan(Number(effect,"fov",t,50,10,140)*3.14159265358979323846/360);
    const auto& samples=effect.particles->system.sample(c,(layer.steppedMs(timeMs)-layer.startTime)/1000.0);
    out.reserve(samples.size());
    for (const auto& particle:samples) {
        Vec p=particles::InverseRotate(particle.position-camera,rotation);
        if (p.z<=.05 || particle.radius<=0 || particle.color[3]<=0) continue;
        double angle=particle.rotation;
        if(c.alignVelocity) {
            Vec v=particles::InverseRotate(particle.velocity,rotation);
            const Vec dir{v.x*p.z-p.x*v.z,v.y*p.z-p.y*v.z,0};
            if(dir.length()>1e-9) angle+=std::atan2(dir.y,dir.x)*180/3.14159265358979323846;
        }
        Sprite s{p.x*focal/p.z,p.y*focal/p.z,particle.radius*focal/p.z,angle,&particle};
        if(std::isfinite(s.x)&&std::isfinite(s.y)&&std::isfinite(s.r)&&s.r<1e7) out.push_back(s);
    }
    return out;
}

std::vector<Sprite> WorldSprites(const Layer& layer, const LayerEffect& effect, double timeMs) {
    if (!effect.particles || effect.hidden) return {};
    Placement p;
    if (!layer.projected || !SeenPlacement(layer,timeMs,&p)) return Sprites(layer,effect,timeMs);
    const float t=layer.localTime(timeMs);
    const Config c=Settings(effect,t);
    const double units=Number(effect,"worldScale",t,300,1,100000);
    const double scaleZ=std::sqrt(std::fabs(p.scale.fX*p.scale.fY));
    const SkM44 model=p.space*(p.fullTransform?SkM44():SkM44::Scale(p.scale.fX,p.scale.fY,scaleZ)*SkM44::Translate(-p.pivot.fX,-p.pivot.fY));
    const Layer* camera=layer.sight.camera;
    const float fov=camera?camera->camera.fov.get(0,camera->localTime(timeMs),60):60;
    const double focal=std::max(layer.sight.width,layer.sight.height)/(2*std::tan(std::clamp(fov,.1f,179.9f)*3.14159265358979323846/360));
    double roll=p.rotation;
    if(layer.sight.camera&&layer.sight.layers) roll-=EffectivePlacement(*layer.sight.layers,*layer.sight.camera,timeMs).rotation;
    const auto& samples=effect.particles->system.sample(c,(layer.steppedMs(timeMs)-layer.startTime)/1000.0);
    std::vector<Sprite> out;out.reserve(samples.size());
    for(const auto& particle:samples) {
        const Vec v=particle.position*units;
        const SkV4 q=model.map(v.x,v.y,v.z,1);
        if(!(q.w>=1) || !(q.z>=1) || particle.radius<=0 || particle.color[3]<=0) continue;
        double angle=particle.rotation+roll;
        if(c.alignVelocity) {
            const Vec next=v+particle.velocity*units;
            const SkV4 b=model.map(next.x,next.y,next.z,1);
            if(b.w>=1) {
                const double dx=b.x/b.w-q.x/q.w,dy=b.y/b.w-q.y/q.w;
                if(std::hypot(dx,dy)>1e-9) angle=particle.rotation+std::atan2(dy,dx)*180/3.14159265358979323846;
            }
        }
        Sprite sprite{q.x/q.w,q.y/q.w,particle.radius*units*scaleZ*focal/q.w,angle,&particle,q.z};
        if(std::isfinite(sprite.x)&&std::isfinite(sprite.y)&&std::isfinite(sprite.r)&&sprite.r<1e7) out.push_back(sprite);
    }
    std::stable_sort(out.begin(),out.end(),[](const Sprite& a,const Sprite& b){return a.depth>b.depth;});
    return out;
}
}

bool ParticleEffect(const std::string& id) { return id=="lab.true.particles" || id=="lab.toji.particles3d"; }
bool HasParticles(const Layer& layer) {
    return std::any_of(layer.effects.begin(),layer.effects.end(),[](const LayerEffect& e){return !e.hidden&&ParticleEffect(e.id);});
}
bool HasWorldParticles(const Layer& layer,double timeMs) {
    return std::any_of(layer.effects.begin(),layer.effects.end(),[&](const LayerEffect& e){return !e.hidden&&ParticleEffect(e.id)&&World(e,layer.localTime(timeMs));});
}
SkRect ParticleSceneBounds(const Layer& layer,double timeMs,const SkMatrix& localToScene) {
    SkRect bounds=SkRect::MakeEmpty();
    for(const LayerEffect& e:layer.effects) if(ParticleEffect(e.id)&&!e.hidden) {
        const bool world=World(e,layer.localTime(timeMs));
        const double aspect=Number(e,"spriteAspect",layer.localTime(timeMs),1,.01,100);
        for(const Sprite& s:world?WorldSprites(layer,e,timeMs):Sprites(layer,e,timeMs)) {
            const float r=s.r*std::sqrt(1+aspect*aspect);
            SkRect box=SkRect::MakeLTRB(s.x-r,s.y-r,s.x+r,s.y+r);
            bounds.join(world?box:localToScene.mapRect(box));
        }
    }
    return bounds;
}
SkRect ParticleViewBounds(const Scene& scene,const Layer& layer,double timeMs) {
    Layer seen=layer;
    seen.placed=seen.projected=true;
    seen.sight={&scene.layers,&layer,CameraViewOn()?ActiveCamera(scene.layers,timeMs):nullptr,scene.width,scene.height};
    return ParticleSceneBounds(seen,timeMs,SkMatrix::I());
}
SkRect ParticleBounds(const Layer& layer,double timeMs) {
    SkRect bounds=SkRect::MakeEmpty();
    for(const LayerEffect& e:layer.effects) if(ParticleEffect(e.id)&&!e.hidden) {
        const float t=layer.localTime(timeMs);
        const double aspect=Number(e,"spriteAspect",t,1,.01,100);
        for(const Sprite& s:Sprites(layer,e,timeMs)) {
            const float r=s.r*std::sqrt(1+aspect*aspect);
            bounds.join(SkRect::MakeLTRB(s.x-r,s.y-r,s.x+r,s.y+r));
        }
    }
    // Keep an empty emitter selectable without cropping the actual generated geometry.
    return bounds.isEmpty()?SkRect::MakeLTRB(-12,-12,12,12):bounds;
}
void DrawParticles(SkCanvas* canvas,const Layer& layer,double timeMs,float alpha,const SkMatrix& localToScene,const ParticleTexture& texture) {
    thread_local sk_sp<SkShader> dot=[] {
        const SkColor4f colors[]={{1,1,1,1},{1,1,1,0}};
        const SkScalar stops[]={.2f,1};
        return SkShaders::RadialGradient({0,0},1,SkGradient(SkGradient::Colors(colors,stops,SkTileMode::kClamp),{}));
    }();
    for(const LayerEffect& e:layer.effects) if(ParticleEffect(e.id)&&!e.hidden) {
        const float t=layer.localTime(timeMs);
        const bool textured=Number(e,"useTexture",t,0,0,1)>.5;
        const auto image=textured?texture(e):nullptr;
        if(textured&&!image) continue;
        const double aspect=Number(e,"spriteAspect",t,1,.01,100);
        const bool additive=Number(e,"additive",t,1,0,1)>.5;
        const bool world=World(e,t);
        canvas->save();
        if(!world) canvas->concat(localToScene);
        for(const Sprite& s:world?WorldSprites(layer,e,timeMs):Sprites(layer,e,timeMs)) {
            const auto& c=s.particle->color;
            SkPaint paint;
            paint.setAntiAlias(true);
            paint.setBlendMode(additive?SkBlendMode::kPlus:SkBlendMode::kSrcOver);
            paint.setAlphaf(std::clamp(alpha*(float)c[3],0.0f,1.0f));
            paint.setColorFilter(SkColorFilters::Blend({(float)c[0],(float)c[1],(float)c[2],1},nullptr,SkBlendMode::kModulate));
            canvas->save(); canvas->translate(s.x,s.y); canvas->rotate(s.angle); canvas->scale(s.r,s.r);
            if(image) canvas->drawImageRect(image,SkRect::MakeLTRB(-aspect,-1,aspect,1),SkSamplingOptions(SkFilterMode::kLinear),&paint);
            else { paint.setShader(dot); canvas->drawCircle(0,0,1,paint); }
            canvas->restore();
        }
        canvas->restore();
    }
}
}
