package app.truemotion.port;

import org.json.*;
import org.mozilla.javascript.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** The legacy animate(env, el, p) ABI. All values crossing this boundary are JSON. */
public final class LegacyRuntime {
    private LegacyRuntime() {}
    static final class Limit extends Error { Limit() { super("Legacy animation budget exceeded"); } }
    static final class Budget { int instructions; long deadline = System.nanoTime()+500_000_000L; }
    private static final ThreadLocal<Budget> budget = new ThreadLocal<>();
    private static final ContextFactory factory = new ContextFactory() {
        @Override protected Context makeContext() {
            Context c=super.makeContext(); c.setOptimizationLevel(-1); c.setLanguageVersion(Context.VERSION_ES6);
            c.setInstructionObserverThreshold(200); c.setMaximumInterpreterStackDepth(100);
            c.setClassShutter(name->false); return c;
        }
        @Override protected void observeInstructionCount(Context c,int n) {
            Budget b=budget.get(); b.instructions+=n;
            if(b.instructions>200000 || System.nanoTime()>b.deadline) throw new Limit();
        }
    };
    private static final ThreadLocal<LinkedHashMap<String,Function>> functions = ThreadLocal.withInitial(()->new LinkedHashMap<String,Function>(32,.75f,true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String,Function> e) { return size()>128; }
    });
    private static final String SAFETY = "eval=undefined;Date=undefined;"+
        "var __fp=Function.prototype;__fp.constructor=function(){throw Error('Dynamic code is unavailable');};Function=__fp.constructor;"+
        "String.prototype.repeat=function(n){n=Math.floor(n);if(n<0||n>8192||this.length*n>65536)throw Error('Text is too long');return Array(n+1).join(this);};";

    public static byte[] evaluate(byte[] identity,byte[] source,byte[] input) {
        if(source.length>262144 || input.length>1048576) return error("Legacy input is too large");
        budget.set(new Budget()); Context cx=factory.enterContext();
        try {
            String id=new String(identity,StandardCharsets.UTF_8), code=new String(source,StandardCharsets.UTF_8);
            // These donor expressions use ASCII character classes. Rhino has no ES2015
            // Unicode regexp flag; removing it leaves their numeric matching unchanged.
            if(id.equals("tmold.com.alightcreative.effects.counter"))code=code.replace("/iu;","/i;").replace("/giu)","/gi)");
            LinkedHashMap<String,Function> cache=functions.get(); String key=id+'\n'+code;
            Function runner=cache.get(key);
            if(runner==null) {
                ScriptableObject scope=cx.initSafeStandardObjects();
                ScriptableObject.putProperty(scope,"__legacyMath",ScriptableObject.getProperty(scope,"Math"));
                ScriptableObject.putProperty(scope,"__noise",new BaseFunction() {
                    @Override public Object call(Context c,Scriptable s,Scriptable self,Object[] a) {
                        double x=a.length>0?Context.toNumber(a[0]):0,y=a.length>1?Context.toNumber(a[1]):0,z=a.length>2?Context.toNumber(a[2]):0;
                        return a.length>2?Noise.three(x,y,z):Noise.two(x,y);
                    }
                });
                // Per-call locals isolate assignments and mutable top-level script state.
                String wrapper="(function(__input,__seed){"+SAFETY+
                    "var Math=Object.create(__legacyMath);"+
                    "Math.random=function(){__seed=(__legacyMath.imul(__seed,1664525)+1013904223)|0;return (__seed>>>0)/4294967296;};"+
                    "var AM={simplexNoise:__noise,triangle:function(x){return (Math.abs(((x+.75)%1)-.5)-.25)*4;},log:function(){}};"+
                    code+";var q=JSON.parse(__input);var r=animate(q.env,q.el,q.p);return JSON.stringify(r||q.el);})";
                runner=(Function)cx.evaluateString(scope,wrapper,id,1,null); cache.put(key,runner);
            }
            budget.get().deadline=System.nanoTime()+500_000_000L;
            String result=Context.toString(runner.call(cx,runner.getParentScope(),runner.getParentScope(),new Object[]{new String(input,StandardCharsets.UTF_8),31*id.hashCode()+Arrays.hashCode(input)}));
            if(result.length()>262144) throw new Limit();
            JSONObject r=new JSONObject(result); StringBuilder xml=new StringBuilder("<result");
            JSONObject tf=r.optJSONObject("transform");
            if(tf!=null) {
                vector(xml,tf,"location",new String[]{"x","y","z"});
                vector(xml,tf,"pivot",new String[]{"x","y"}); vector(xml,tf,"scale",new String[]{"x","y"});
                number(xml,"angle",tf.optDouble("angle",Double.NaN));
            }
            number(xml,"alpha",r.optDouble("alpha",Double.NaN)); xml.append('>');
            JSONArray groups=r.optJSONArray("shaderGroups");
            if(groups!=null) { if(groups.length()>64) throw new Limit(); for(int i=0;i<groups.length();i++) { int g=groups.getInt(i); if(g<0||g>255)throw new IllegalArgumentException("Invalid shader group");xml.append("<group value=\"").append(g).append("\"/>"); } }
            if(r.has("text")) {String text=r.getString("text"); if(text.length()>65536)throw new Limit();xml.append("<text>").append(escape(text)).append("</text>");}
            return xml.append("</result>").toString().getBytes(StandardCharsets.UTF_8);
        } catch(Throwable e) { return error(e.getMessage()==null?e.toString():e.getMessage()); }
        finally {Context.exit();budget.remove();}
    }
    private static void vector(StringBuilder b,JSONObject tf,String name,String[] keys) throws JSONException {
        JSONObject v=tf.optJSONObject(name); if(v==null)return;
        for(int i=0;i<keys.length;i++)number(b,name+i,v.optDouble(keys[i],Double.NaN));
    }
    private static void number(StringBuilder b,String name,double v) {if(Double.isFinite(v)&&Math.abs(v)<1e12)b.append(' ').append(name).append("=\"").append(v).append('"');}
    private static byte[] error(String message) {return ("<result error=\""+escape(message.substring(0,Math.min(1024,message.length())))+"\"/>").getBytes(StandardCharsets.UTF_8);}
    private static String escape(String s) {return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;");}

    /** Standard simplex noise; canonical permutation, independent of frame visitation order. */
    static final class Noise {
        static final int[] P={151,160,137,91,90,15,131,13,201,95,96,53,194,233,7,225,140,36,103,30,69,142,8,99,37,240,21,10,23,190,6,148,247,120,234,75,0,26,197,62,94,252,219,203,117,35,11,32,57,177,33,88,237,149,56,87,174,20,125,136,171,168,68,175,74,165,71,134,139,48,27,166,77,146,158,231,83,111,229,122,60,211,133,230,220,105,92,41,55,46,245,40,244,102,143,54,65,25,63,161,1,216,80,73,209,76,132,187,208,89,18,169,200,196,135,130,116,188,159,86,164,100,109,198,173,186,3,64,52,217,226,250,124,123,5,202,38,147,118,126,255,82,85,212,207,206,59,227,47,16,58,17,182,189,28,42,223,183,170,213,119,248,152,2,44,154,163,70,221,153,101,155,167,43,172,9,129,22,39,253,19,98,108,110,79,113,224,232,178,185,112,104,218,246,97,228,251,34,242,193,238,210,144,12,191,179,162,241,81,51,145,235,249,14,239,107,49,192,214,31,181,199,106,157,184,84,204,176,115,121,50,45,127,4,150,254,138,236,205,93,222,114,67,29,24,72,243,141,128,195,78,66,215,61,156,180};
        static final int[][] G={{1,1,0},{-1,1,0},{1,-1,0},{-1,-1,0},{1,0,1},{-1,0,1},{1,0,-1},{-1,0,-1},{0,1,1},{0,-1,1},{0,1,-1},{0,-1,-1}};
        static int p(int i){return P[i&255];}
        static double dot(int h,double x,double y,double z){int[] g=G[h%12];return g[0]*x+g[1]*y+g[2]*z;}
        static double term(int h,double x,double y,double z,double radius){double t=radius-x*x-y*y-z*z;return t<0?0:t*t*t*t*dot(h,x,y,z);}
        static double two(double x,double y){double f=(Math.sqrt(3)-1)/2,g=(3-Math.sqrt(3))/6,s=(x+y)*f;int i=(int)Math.floor(x+s),j=(int)Math.floor(y+s);double t=(i+j)*g,a=x-i+t,b=y-j+t;int i1=a>b?1:0,j1=1-i1;return 70*(term(p(i+p(j)),a,b,0,.5)+term(p(i+i1+p(j+j1)),a-i1+g,b-j1+g,0,.5)+term(p(i+1+p(j+1)),a-1+2*g,b-1+2*g,0,.5));}
        static double three(double x,double y,double z){double s=(x+y+z)/3;int i=(int)Math.floor(x+s),j=(int)Math.floor(y+s),k=(int)Math.floor(z+s);double t=(i+j+k)/6.0,a=x-i+t,b=y-j+t,c=z-k+t;int i1,j1,k1,i2,j2,k2;
            if(a>=b){if(b>=c){i1=1;j1=0;k1=0;i2=1;j2=1;k2=0;}else if(a>=c){i1=1;j1=0;k1=0;i2=1;j2=0;k2=1;}else{i1=0;j1=0;k1=1;i2=1;j2=0;k2=1;}}
            else{if(b<c){i1=0;j1=0;k1=1;i2=0;j2=1;k2=1;}else if(a<c){i1=0;j1=1;k1=0;i2=0;j2=1;k2=1;}else{i1=0;j1=1;k1=0;i2=1;j2=1;k2=0;}}
            return 32*(term(p(i+p(j+p(k))),a,b,c,.6)+term(p(i+i1+p(j+j1+p(k+k1))),a-i1+1/6.0,b-j1+1/6.0,c-k1+1/6.0,.6)+term(p(i+i2+p(j+j2+p(k+k2))),a-i2+1/3.0,b-j2+1/3.0,c-k2+1/3.0,.6)+term(p(i+1+p(j+1+p(k+1))),a-.5,b-.5,c-.5,.6));}
    }
}
