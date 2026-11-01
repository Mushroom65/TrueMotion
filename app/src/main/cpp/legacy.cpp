#include "legacy.h"
#include <mutex>
#include <sstream>
#include <iomanip>
#include <algorithm>
#include <cmath>

namespace am {
namespace {
struct Definition {
    std::string source;
    std::map<std::string,Property> params;
};
std::mutex registryMutex;
std::map<std::string,std::shared_ptr<const Definition>> definitions;
LegacyCallback evaluate;
std::shared_ptr<const Definition> Find(const std::string& id) {
    if(id.compare(0,6,"tmold.")!=0)return nullptr;
    std::lock_guard<std::mutex> lock(registryMutex);
    const auto it=definitions.find(id);return it==definitions.end()?nullptr:it->second;
}
std::string Quote(const std::string& s) {
    std::ostringstream out;out<<'"';
    for(unsigned char c:s) {if(c=='"'||c=='\\')out<<'\\'<<(char)c;else if(c<32)out<<"\\u00"<<std::hex<<std::setw(2)<<std::setfill('0')<<(int)c<<std::dec;else out<<(char)c;}
    out<<'"';return out.str();
}
void Number(std::ostream& o,double v) {o<<(std::isfinite(v)?v:0);}
void Vector(std::ostream& o,const std::vector<float>& v,const char* const* names) {
    o<<'{';for(size_t i=0;i<v.size();i++){if(i)o<<',';o<<Quote(names[i])<<':';Number(o,v[i]);}o<<'}';
}
const char* const xyz[]={"x","y","z","w"};
const char* const rgba[]={"a","r","g","b"};
Placement Raw(const Layer& l,float t) {
    Placement p{};p.location={l.location.get(0,t,0),l.location.get(1,t,0)};
    p.z=l.location.get(2,t,0);p.pivot={l.pivot.get(0,t,0),l.pivot.get(1,t,0)};
    p.scale={l.scale.get(0,t,1),l.scale.get(1,t,1)};p.rotation=l.rotation.get(0,t,0);return p;
}
std::unique_ptr<XmlNode> Run(const Layer& l,const LayerEffect& e,double ms,const Placement* placement=nullptr,const std::string* text=nullptr) {
    const auto d=Find(e.id);if(!d||d->source.empty()||!evaluate)return nullptr;
    const float t=l.localTime(ms);const Placement p=placement?*placement:Raw(l,t);
    const double duration=(l.endTime-l.startTime)/1000.0,dt=duration>0?1/(60*duration):0;
    const Placement before=Raw(l,t-dt),after=Raw(l,t+dt);
    std::ostringstream q;q<<std::setprecision(12)<<"{\"env\":{\"time\":";Number(q,t);
    q<<",\"absTime\":";Number(q,l.steppedMs(ms)/1000.0);q<<",\"duration\":";Number(q,duration);
    const int fps=std::max(ProjectFps(),1);
    q<<",\"startTime\":"<<l.startTime/1000.0<<",\"inTime\":"<<l.startTime/1000.0<<",\"endTime\":"<<l.endTime/1000.0;
    q<<",\"fps\":"<<fps<<",\"frame\":"<<(int)std::floor(ms*fps/1000)<<",\"prevFrameTime\":";Number(q,t-(duration>0?1/(fps*duration):0));
    q<<",\"velocity\":";Vector(q,{(after.location.fX-before.location.fX)*30,(after.location.fY-before.location.fY)*30},xyz);
    q<<",\"angularVelocity\":";Number(q,(after.rotation-before.rotation)*30);
    q<<",\"scaleVelocity\":";Number(q,(after.scale.fX-before.scale.fX)*30);
    q<<",\"selected\":false,\"editMode\":false},\"el\":{\"alpha\":1,\"transform\":{\"location\":";Vector(q,{p.location.fX,p.location.fY,p.z},xyz);
    q<<",\"pivot\":";Vector(q,{p.pivot.fX,p.pivot.fY},xyz);q<<",\"scale\":";Vector(q,{p.scale.fX,p.scale.fY},xyz);
    q<<",\"angle\":";Number(q,p.rotation);q<<'},';
    q<<"\"shaderGroups\":[0]";
    const std::string content=text?*text:TextContent(l);if(l.text)q<<",\"text\":"<<Quote(content);
    q<<"},\"p\":{";bool comma=false;
    for(const auto& item:d->params) {
        const Property* v=e.prop(item.first.c_str());const Property& p=v?*v:item.second;
        if(comma)q<<',';comma=true;q<<Quote(item.first)<<':';
        const auto val=[&](int i){return p.anim.get(i,t,item.second.anim.get(i,t,0));};
        const auto& type=item.second.type;
        if(type=="string")q<<Quote(v?v->text:item.second.text);
        else if(type=="bool")q<<(val(0)>.5?"true":"false");
        else if(type=="vec2")Vector(q,{val(0),val(1)},xyz);
        else if(type=="vec3")Vector(q,{val(0),val(1),val(2)},xyz);
        else if(type=="quat")Vector(q,{val(0),val(1),val(2),val(3)},xyz);
        else if(type=="color")Vector(q,{val(0),val(1),val(2),val(3)},rgba);
        else Number(q,val(0));
    }
    q<<"}}";const std::string input=q.str();
    // Placement, text and group consumers share results; cache keys include every evaluated value.
    thread_local std::map<std::string,std::string> results;
    const std::string key=e.id+'\n'+input;auto found=results.find(key);
    std::string result;if(found!=results.end())result=found->second;else {
        result=evaluate(e.id,d->source,input);if(results.size()>=128)results.clear();results[key]=result;
    }
    std::string error;return ParseXml(result,&error);
}
float Attr(const XmlNode& result,const char* name,float fallback) {const char* s=result.attr(name);if(!s)return fallback;const float v=std::strtof(s,nullptr);return std::isfinite(v)?v:fallback;}
}
void SetLegacyCallback(LegacyCallback callback) {evaluate=std::move(callback);}
void RegisterLegacy(const std::string& id,const XmlNode& root) {
    if(!root.attr("legacyScript"))return;
    auto d=std::make_shared<Definition>();d->source=root.attr("legacyScript");
    for(const auto& c:root.children)if(c->name=="param"&&c->attr("name")) {
        Property p;p.name=c->attr("name");p.type=c->attr("type")?c->attr("type"):"float";p.anim=ParseAnimNode(c.get());
        if(c->attr("value"))p.text=c->attr("value");d->params[p.name]=std::move(p);
    }
    std::lock_guard<std::mutex> lock(registryMutex);definitions[id]=std::move(d);
}
bool LegacyScript(const std::string& id) {const auto d=Find(id);return d&&!d->source.empty();}
bool LegacyTextEffect(const Layer& l) {return l.text&&std::any_of(l.effects.begin(),l.effects.end(),[](const auto& e){const auto d=e.hidden?nullptr:Find(e.id);return d&&d->source.find(".text")!=std::string::npos;});}
void ApplyLegacy(const Layer& l,const LayerEffect& e,double ms,Placement* p) {
    const auto d=Find(e.id);if(!d||d->source.find(".transform")==std::string::npos)return;
    const auto r=Run(l,e,ms,p);if(!r||r->attr("error"))return;
    p->location={Attr(*r,"location0",p->location.fX),Attr(*r,"location1",p->location.fY)};
    p->z=Attr(*r,"location2",p->z);p->pivot={Attr(*r,"pivot0",p->pivot.fX),Attr(*r,"pivot1",p->pivot.fY)};
    p->scale={Attr(*r,"scale0",p->scale.fX),Attr(*r,"scale1",p->scale.fY)};p->rotation=Attr(*r,"angle",p->rotation);
}
float LegacyOpacity(const Layer& l,double ms) {
    float opacity=1;for(const auto& e:l.effects)if(!e.hidden) {const auto d=Find(e.id);if(!d||d->source.find(".alpha")==std::string::npos)continue;const auto r=Run(l,e,ms);if(r&&!r->attr("error"))opacity*=Attr(*r,"alpha",1);}return opacity;
}
std::string LegacyText(const Layer& l,float t,const std::string& content) {
    std::string text=content;
    for(const auto& e:l.effects)if(!e.hidden) {const auto d=Find(e.id);if(!d||d->source.find(".text")==std::string::npos)continue;const auto r=Run(l,e,l.startTime+t*(l.endTime-l.startTime),nullptr,&text);if(r&&!r->attr("error"))if(const auto n=r->child("text"))text=n->text;}
    return text;
}
std::vector<int> LegacyGroups(const Layer& l,const LayerEffect& e,double ms) {
    const auto d=Find(e.id);if(!d||d->source.find(".shaderGroups")==std::string::npos)return {0};
    const auto r=Run(l,e,ms);if(!r||r->attr("error"))return {0};
    std::vector<int> groups;for(const auto& c:r->children)if(c->name=="group")groups.push_back((int)Attr(*c,"value",0));return groups;
}
}
