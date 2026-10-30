package app.truemotion.port;

import java.util.*;

/** A bounded expression language. Compiled once; no scripts, I/O or global state. */
public final class ExpressionMath {
    public interface Sampler { double[] sample(double seconds); }
    public static final class Context {
        public double time, absTime, duration, first, last;
        public long seed;
        public double[] value;
        public Sampler sampler;
    }
    interface Node { double[] eval(Context c); }
    private final Node root;
    private ExpressionMath(Node root) { this.root=root; }
    public static ExpressionMath compile(String source) {
        if(source.length()>2048)throw new IllegalArgumentException("Expression exceeds 2048 characters");
        Parser p=new Parser(source);Node n=p.expression(0);p.space();if(p.at!=source.length())throw p.error("Unexpected token");return new ExpressionMath(n);
    }
    public double[] evaluate(Context c) {
        double[] result=root.eval(c);
        if(result.length!=c.value.length)throw new IllegalArgumentException("Expected "+c.value.length+" components, got "+result.length);
        for(double v:result)if(!Double.isFinite(v)||Math.abs(v)>10000000)throw new IllegalArgumentException("Non-finite or excessive result");
        return result;
    }
    static double scalar(double[] v){if(v.length!=1)throw new IllegalArgumentException("Expected a number");return v[0];}
    static double[] one(double v){return new double[]{v};}
    static double[] binary(double[] a,double[] b,char op){
        int n=Math.max(a.length,b.length);if(a.length!=1&&b.length!=1&&a.length!=b.length)throw new IllegalArgumentException("Vector dimensions differ");
        double[] r=new double[n];for(int i=0;i<n;i++){double x=a[a.length==1?0:i],y=b[b.length==1?0:i];switch(op){case '+':r[i]=x+y;break;case '-':r[i]=x-y;break;case '*':r[i]=x*y;break;case '/':r[i]=x/y;break;case '%':r[i]=x%y;break;default:r[i]=Math.pow(x,y);}}return r;
    }
    static double clamp(double x,double lo,double hi){return Math.max(lo,Math.min(hi,x));}
    static double random(long tick,long seed,int component){
        int x=(int)tick^(int)(tick>>>32)^((int)seed*0x9e3779b9)^(component*0x85ebca6b);x^=x>>>16;x*=0x7feb352d;x^=x>>>15;x*=0x846ca68b;x^=x>>>16;return (x&0xffffffffL)/4294967296.0;
    }
    static double[] function(String name,Node[] nodes,String mode,Context c){
        double[][] a=new double[nodes.length][];for(int i=0;i<a.length;i++)a[i]=nodes[i].eval(c);
        if(name.equals("valueAtTime"))return c.sampler.sample(scalar(a[0]));
        if(name.equals("loopOut")){
            if(c.last<=c.first||c.time<=c.last)return c.value.clone();double span=c.last-c.first,cycles=Math.floor((c.time-c.first)/span),u=(c.time-c.first)/span-cycles;
            if("pingpong".equals(mode)&&((long)cycles&1)!=0)u=1-u;
            double[] r=c.sampler.sample(c.first+u*span);
            if("offset".equals(mode))r=binary(r,binary(binary(c.sampler.sample(c.last),c.sampler.sample(c.first),'-'),one(cycles),'*'),'+');return r;
        }
        if(name.equals("wiggle")){
            double freq=scalar(a[0]);if(freq<0||freq>10000)throw new IllegalArgumentException("wiggle frequency must be 0..10000");
            long seed=c.seed+(a.length==3?(long)scalar(a[2]):0);double phase=c.time*freq;if(Math.abs(phase)>9e15)throw new IllegalArgumentException("Noise time out of range");long tick=(long)Math.floor(phase);double u=phase-tick;u=u*u*(3-2*u);double[] r=c.value.clone();
            if(a[1].length!=1&&a[1].length!=r.length)throw new IllegalArgumentException("wiggle amplitude dimensions differ");
            for(int i=0;i<r.length;i++){double n=random(tick,seed,i)*(1-u)+random(tick+1,seed,i)*u;r[i]+=(n*2-1)*a[1][a[1].length==1?0:i];}return r;
        }
        if(name.equals("random")){double lo=a.length>=2?scalar(a[0]):0,hi=a.length>=1?scalar(a[a.length>=2?1:0]):1;return one(lo+(hi-lo)*random(0,c.seed+(a.length==3?(long)scalar(a[2]):0),0));}
        if(name.equals("linear")||name.equals("ease")){
            double x=scalar(a[0]),lo=scalar(a[1]),hi=scalar(a[2]);if(hi==lo)throw new IllegalArgumentException("Equal range endpoints");double t=clamp((x-lo)/(hi-lo),0,1);if(name.equals("ease"))t=t*t*(3-2*t);return binary(a[3],binary(binary(a[4],a[3],'-'),one(t),'*'),'+');
        }
        if(name.equals("clamp")){
            double[] r=a[0].clone();for(int i=0;i<r.length;i++)r[i]=clamp(r[i],scalar(a[1]),scalar(a[2]));return r;
        }
        if(name.equals("min")||name.equals("max")||name.equals("pow")){
            if(name.equals("pow"))return binary(a[0],a[1],'^');double[] r=a[0];for(int j=1;j<a.length;j++){int n=Math.max(r.length,a[j].length);if(r.length!=1&&a[j].length!=1&&r.length!=a[j].length)throw new IllegalArgumentException("Vector dimensions differ");double[] next=new double[n];for(int i=0;i<n;i++)next[i]=name.equals("min")?Math.min(r[r.length==1?0:i],a[j][a[j].length==1?0:i]):Math.max(r[r.length==1?0:i],a[j][a[j].length==1?0:i]);r=next;}return r;
        }
        double[] r=a[0].clone();for(int i=0;i<r.length;i++){double x=r[i];switch(name){case "sin":r[i]=Math.sin(x);break;case "cos":r[i]=Math.cos(x);break;case "tan":r[i]=Math.tan(x);break;case "abs":r[i]=Math.abs(x);break;case "sqrt":r[i]=Math.sqrt(x);break;case "floor":r[i]=Math.floor(x);break;case "ceil":r[i]=Math.ceil(x);break;case "round":r[i]=Math.floor(x+.5);break;case "exp":r[i]=Math.exp(x);break;case "log":r[i]=Math.log(x);break;case "radians":r[i]=Math.toRadians(x);break;case "degrees":r[i]=Math.toDegrees(x);break;default:throw new IllegalArgumentException("Unknown function: "+name);}}return r;
    }
    static final class Parser {
        final String text;int at,nodes,depth;
        Parser(String s){text=s;}
        IllegalArgumentException error(String message){return new IllegalArgumentException(message+" @"+(at+1));}
        void space(){while(at<text.length()&&Character.isWhitespace(text.charAt(at)))at++;}
        boolean take(char ch){space();if(at<text.length()&&text.charAt(at)==ch){at++;return true;}return false;}
        void need(char ch){if(!take(ch))throw error("Expected "+ch);}
        Node expression(int minimum){
            if(++depth>32)throw error("Expression nesting exceeds 32");if(++nodes>512)throw error("Too many terms");
            Node left;
            if(take('+'))left=expression(30);else if(take('-')){final Node x=expression(30);left=c->binary(one(0),x.eval(c),'-');}
            else if(take('(')){left=expression(0);need(')');}
            else if(take('[')){
                List<Node> list=new ArrayList<Node>();list.add(expression(0));while(take(','))list.add(expression(0));need(']');if(list.size()<2||list.size()>3)throw error("Vectors require 2 or 3 components");final Node[] items=list.toArray(new Node[0]);left=c->{double[] r=new double[items.length];for(int i=0;i<r.length;i++)r[i]=scalar(items[i].eval(c));return r;};
            }else{
                space();int begin=at;
                if(at<text.length()&&(Character.isDigit(text.charAt(at))||text.charAt(at)=='.')){
                    while(at<text.length()&&(Character.isDigit(text.charAt(at))||text.charAt(at)=='.'))at++;
                    if(at<text.length()&&(text.charAt(at)=='e'||text.charAt(at)=='E')){at++;if(at<text.length()&&(text.charAt(at)=='+'||text.charAt(at)=='-'))at++;while(at<text.length()&&Character.isDigit(text.charAt(at)))at++;}
                    final double v;try{v=Double.parseDouble(text.substring(begin,at));}catch(Exception e){throw error("Invalid number");}if(!Double.isFinite(v))throw error("Invalid number");left=c->one(v);
                }else{
                    while(at<text.length()&&(Character.isLetterOrDigit(text.charAt(at))||text.charAt(at)=='_'||text.charAt(at)=='.'))at++;
                    if(at==begin)throw error("Expected a number, variable or function");String id=text.substring(begin,at);if(id.startsWith("Math."))id=id.substring(5);final String name=id;
                    if(take('(')){
                        List<Node> args=new ArrayList<Node>();String mode="cycle";
                        if(name.equals("loopOut")){space();if(at<text.length()&&(text.charAt(at)=='\"'||text.charAt(at)=='\'')){char quote=text.charAt(at++);int b=at;while(at<text.length()&&text.charAt(at)!=quote)at++;if(at==text.length())throw error("Unclosed string");mode=text.substring(b,at++);if(!Arrays.asList("cycle","pingpong","offset").contains(mode))throw error("Unknown loop mode");}need(')');}
                        else if(!take(')')){args.add(expression(0));while(take(','))args.add(expression(0));need(')');}
                        int n=args.size();boolean valid=name.equals("loopOut")?n==0:name.equals("wiggle")?n==2||n==3:name.equals("random")?n<=3:name.equals("linear")||name.equals("ease")?n==5:name.equals("clamp")?n==3:name.equals("pow")?n==2:name.equals("min")||name.equals("max")?n>=2&&n<=8:Arrays.asList("sin","cos","tan","abs","sqrt","floor","ceil","round","exp","log","radians","degrees","valueAtTime").contains(name)&&n==1;
                        if(!valid)throw error("Unknown function or argument count: "+name);final Node[] items=args.toArray(new Node[0]);final String loop=mode;left=c->function(name,items,loop,c);
                    }else if(name.equals("value"))left=c->c.value.clone();
                    else if(name.equals("time"))left=c->one(c.time);
                    else if(name.equals("absTime"))left=c->one(c.absTime);
                    else if(name.equals("duration"))left=c->one(c.duration);
                    else if(name.equals("pi")||name.equals("PI"))left=c->one(Math.PI);
                    else if(name.equals("seed"))left=c->one(c.seed);
                    else throw error("Unknown variable: "+name);
                }
            }
            while(take('[')){space();int b=at;while(at<text.length()&&Character.isDigit(text.charAt(at)))at++;if(b==at)throw error("Expected component index");final int index=Integer.parseInt(text.substring(b,at));need(']');final Node v=left;left=c->{double[] r=v.eval(c);if(index>=r.length)throw new IllegalArgumentException("Component index out of range");return one(r[index]);};}
            while(true){space();if(at==text.length())break;char op=text.charAt(at);int priority=op=='+'||op=='-'?10:op=='*'||op=='/'||op=='%'?20:op=='^'?30:-1;if(priority<minimum)break;at++;final Node a=left,b=expression(priority+(op=='^'?0:1));left=c->binary(a.eval(c),b.eval(c),op);}
            depth--;return left;
        }
    }
}
