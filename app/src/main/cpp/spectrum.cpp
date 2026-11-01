#include "spectrum.h"
#include "include/core/SkCanvas.h"
#include "include/core/SkPaint.h"
#include "include/core/SkPathBuilder.h"
#include "include/core/SkMaskFilter.h"
#include "include/core/SkBlurTypes.h"
#include "include/effects/SkGradient.h"
#include <cmath>

namespace am {
namespace {
constexpr float pi=3.14159265358979323846f;
float Number(const LayerEffect& e,const char* name,float t,float fallback,float min,float max) {
    const float x=e.value(name,t,fallback);
    return std::clamp(std::isfinite(x)?x:fallback,min,max);
}
SkColor4f Colour(const LayerEffect& e,const char* name,float t,SkColor4f fallback) {
    const Property* p=e.prop(name);if(!p)return fallback;
    return {p->anim.get(1,t,fallback.fR),p->anim.get(2,t,fallback.fG),p->anim.get(3,t,fallback.fB),p->anim.get(0,t,fallback.fA)};
}
bool Radial(int mode) { return mode==3 || mode==4 || mode==6; }
SkRect Bounds(const LayerEffect& e,float t) {
    const float width=Number(e,"width",t,600,1,100000),height=Number(e,"height",t,240,1,100000);
    const int mode=(int)Number(e,"style",t,0,0,6);
    const float r=Number(e,"radius",t,100,0,100000)+height;
    const float pad=Number(e,"thickness",t,4,.1,1000)/2+Number(e,"glow",t,0,0,100)*3+2;
    return (Radial(mode)?SkRect::MakeLTRB(-r,-r,r,r):SkRect::MakeLTRB(-width/2,-height/2,width/2,height/2)).makeOutset(pad,pad);
}
}
bool SpectrumEffect(const std::string& id) { return id=="lab.true.spectrum"; }
bool HasSpectrum(const Layer& layer) {
    return std::any_of(layer.effects.begin(),layer.effects.end(),[](const LayerEffect& e){return !e.hidden&&SpectrumEffect(e.id);});
}
SkRect SpectrumBounds(const Layer& layer,double timeMs) {
    SkRect rect=SkRect::MakeEmpty();
    for(const auto& e:layer.effects)if(!e.hidden&&SpectrumEffect(e.id))rect.join(Bounds(e,layer.localTime(timeMs)));
    return rect;
}
void DrawSpectrum(SkCanvas* canvas,const Layer& layer,double timeMs,float alpha) {
    const float t=layer.localTime(timeMs);
    for(const auto& e:layer.effects) {
        if(e.hidden||!SpectrumEffect(e.id))continue;
        const int style=(int)Number(e,"style",t,0,0,6),count=(int)Number(e,"count",t,64,4,256);
        const float width=Number(e,"width",t,600,1,100000),height=Number(e,"height",t,240,1,100000);
        const float radius=Number(e,"radius",t,100,0,100000),thickness=Number(e,"thickness",t,4,.1,1000);
        const float gap=Number(e,"gap",t,.25,0,.95),angle=Number(e,"angle",t,-90,-36000,36000)*pi/180;
        const float sweep=Number(e,"sweep",t,360,1,360)*pi/180,offset=Number(e,"offsetMs",t,0,-30000,30000);
        const float minimum=Number(e,"minimum",t,0,0,1),floor=Number(e,"floorDb",t,-60,-120,-1);
        const float low=Number(e,"minHz",t,20,20,22049),high=Number(e,"maxHz",t,18000,low+1,22050);
        const bool mirror=Number(e,"mirror",t,0,0,1)>.5f,reverse=Number(e,"reverse",t,0,0,1)>.5f;
        std::vector<float> values(count);
        for(int i=0;i<count;i++) {
            const float u=(reverse?count-1-i:i)/(float)(count-1);
            const float hz=low*std::pow(high/low,u),bin=std::log(hz/20)/std::log(22050.0f/20)*63;
            const int a=std::clamp((int)bin,0,63),b=std::min(a+1,63);
            auto read=[&](int k){return k<(int)e.spectrum.size()&&e.spectrum[k]?e.spectrum[k]->at(layer.steppedMs(timeMs)-offset):0.0f;};
            float power=std::lerp(read(a),read(b),std::clamp(bin-a,0.0f,1.0f));
            if(Number(e,"normalize",t,1,0,1)>.5f)power/=std::max(e.spectrumPeak,1e-6f);
            const float gate=Number(e,"gate",t,0,0,.99f);
            power=std::max(0.0f,(power-gate)/(1-gate))*Number(e,"gain",t,1,0,20);
            const float db=20*std::log10(std::max(power,1e-8f));
            values[i]=std::max(minimum,std::clamp((db-floor)/-floor,0.0f,1.0f));
        }
        SkPathBuilder shape;
        if(style==0) {
            const float step=width/count,bw=step*(1-gap);
            for(int i=0;i<count;i++) {
                const float x=-width/2+step*(i+.5f),h=height*values[i];
                if(h>0)shape.addRect(SkRect::MakeLTRB(x-bw/2,mirror?-h/2:height/2-h,x+bw/2,mirror?h/2:height/2));
            }
        } else if(style==1||style==2) {
            for(int i=0;i<count;i++) {
                const float x=-width/2+width*i/(count-1),y=height/2-height*values[i];
                if(i==0)shape.moveTo(x,y);else shape.lineTo(x,y);
            }
            if(style==2)shape.lineTo(width/2,height/2).lineTo(-width/2,height/2).close();
            if(mirror&&style==1)for(int i=0;i<count;i++) {
                const float x=-width/2+width*i/(count-1),y=-height/2+height*values[i];
                if(i==0)shape.moveTo(x,y);else shape.lineTo(x,y);
            }
        } else if(style==5) {
            for(int i=0;i<count;i++) shape.addCircle(-width/2+width*i/(count-1),height/2-height*values[i],thickness/2);
        } else {
            const bool closed=sweep>2*pi-.001f;
            for(int i=0;i<count;i++) {
                const float a=angle+sweep*i/(closed?count:count-1),r=radius+height*values[i];
                if(style==3) { shape.moveTo(std::cos(a)*radius,std::sin(a)*radius).lineTo(std::cos(a)*r,std::sin(a)*r); }
                else if(i==0)shape.moveTo(std::cos(a)*r,std::sin(a)*r);else shape.lineTo(std::cos(a)*r,std::sin(a)*r);
            }
            if(closed&&style!=3)shape.close();
            else if(style==6)shape.lineTo(0,0).close();
        }
        SkPaint paint;
        paint.setAntiAlias(true);paint.setStrokeWidth(thickness);paint.setStrokeCap(SkPaint::kRound_Cap);paint.setStrokeJoin(SkPaint::kRound_Join);
        paint.setStyle(style==1||style==3||style==4?SkPaint::kStroke_Style:SkPaint::kFill_Style);
        const SkColor4f colors[]={Colour(e,"colorLow",t,{.1f,.9f,.8f,1}),Colour(e,"colorHigh",t,{.8f,.25f,1,1})};
        const SkScalar stops[]={0,1};
        const SkGradient gradient(SkGradient::Colors(colors,stops,SkTileMode::kClamp),{});
        const SkPoint points[]={{-width/2,height/2},{width/2,-height/2}};
        paint.setShader(Radial(style)?SkShaders::RadialGradient({0,0},radius+height,gradient):SkShaders::LinearGradient(points,gradient));
        paint.setAlphaf(std::clamp(alpha,0.0f,1.0f));
        const SkPath path=shape.detach();
        const float glow=Number(e,"glow",t,0,0,100);
        if(glow>0) { paint.setMaskFilter(SkMaskFilter::MakeBlur(kNormal_SkBlurStyle,glow));canvas->drawPath(path,paint);paint.setMaskFilter(nullptr); }
        canvas->drawPath(path,paint);
    }
}
}
