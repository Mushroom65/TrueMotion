package app.truemotion.vector;
import java.util.BitSet;
/** Four-connected colour bucket for an explicitly selected raster image. */
public final class RasterFill {
 static boolean matches(int c,int seed,int tolerance){int a=c>>>24,b=seed>>>24;if(Math.abs(a-b)>tolerance)return false;if(b==0)return true;return Math.abs((c>>16&255)-(seed>>16&255))<=tolerance&&Math.abs((c>>8&255)-(seed>>8&255))<=tolerance&&Math.abs((c&255)-(seed&255))<=tolerance;}
 public static int replace(int[] pixels,int width,int height,int sx,int sy,int color,int tolerance){if(width<=0||height<=0||(long)width*height!=pixels.length||sx<0||sy<0||sx>=width||sy>=height)return 0;tolerance=Math.max(0,Math.min(255,tolerance));int seed=pixels[sy*width+sx];if(seed==color)return 0;int[] queue=new int[pixels.length];BitSet seen=new BitSet(pixels.length);int head=0,tail=1,changed=0;queue[0]=sy*width+sx;seen.set(queue[0]);while(head<tail){int p=queue[head++],x=p%width,y=p/width;if(pixels[p]!=color){pixels[p]=color;changed++;}for(int direction=0;direction<4;direction++){int n=direction==0?(x>0?p-1:-1):direction==1?(x<width-1?p+1:-1):direction==2?(y>0?p-width:-1):(y<height-1?p+width:-1);if(n>=0&&!seen.get(n)){seen.set(n);if(matches(pixels[n],seed,tolerance))queue[tail++]=n;}}}return changed;}
}
