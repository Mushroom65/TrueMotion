package app.truemotion.vector;
import java.util.*;
/** Four-connected bucket region; preserve inner boundaries rather than closing a whole stroke. */
public final class VectorFillRegion {
 public static VectorDocument.Shape trace(boolean[] blocked,int w,int h,int sx,int sy,double scale,int color){
  if(sx<0||sy<0||sx>=w||sy>=h||blocked[sy*w+sx])return null;
  boolean[] found=new boolean[w*h];int[] queue=new int[w*h];int head=0,tail=1;queue[0]=sy*w+sx;found[queue[0]]=true;boolean open=false;
  while(head<tail){int p=queue[head++],x=p%w,y=p/w;if(x==0||y==0||x==w-1||y==h-1)open=true;int[] near={x>0?p-1:-1,x<w-1?p+1:-1,y>0?p-w:-1,y<h-1?p+w:-1};for(int n:near)if(n>=0&&!blocked[n]&&!found[n]){found[n]=true;queue[tail++]=n;}}
  if(open||tail<4)return null;
  int stride=w+1;byte[] edges=new byte[stride*(h+1)];int edgeCount=0;
  for(int i=0;i<tail;i++){int p=queue[i],x=p%w,y=p/w;if(y==0||!found[p-w]){edges[y*stride+x]|=1;edgeCount++;}if(x==w-1||!found[p+1]){edges[y*stride+x+1]|=2;edgeCount++;}if(y==h-1||!found[p+w]){edges[(y+1)*stride+x+1]|=4;edgeCount++;}if(x==0||!found[p-1]){edges[(y+1)*stride+x]|=8;edgeCount++;}}
  if(edgeCount>100000)throw new IllegalArgumentException("Fill boundary is too detailed");
  ArrayList<ArrayList<VectorDocument.Node>> loops=new ArrayList<ArrayList<VectorDocument.Node>>();int[] dx={1,0,-1,0},dy={0,1,0,-1};
  for(int p=0;p<edges.length;p++)while(edges[p]!=0){int x=p%stride,y=p/stride,start=p,dir=Integer.numberOfTrailingZeros(edges[p]&255),guard=0;ArrayList<VectorDocument.Node> nodes=new ArrayList<VectorDocument.Node>();
   do{int at=y*stride+x;nodes.add(new VectorDocument.Node(x/scale,y/scale));edges[at]&=~(1<<dir);x+=dx[dir];y+=dy[dir];int next=y*stride+x;if(next==start)break;int[] choices={(dir+1)%4,dir,(dir+3)%4,(dir+2)%4};boolean ok=false;for(int d:choices)if((edges[next]&(1<<d))!=0){dir=d;ok=true;break;}if(!ok)throw new IllegalArgumentException("Broken fill boundary");if(++guard>edgeCount)throw new IllegalArgumentException("Invalid fill boundary");}while(true);
   // Remove collinear raster steps before the shape's normal simplifier.
   for(int i=nodes.size()-1;i>=0&&nodes.size()>3;i--){VectorDocument.Node a=nodes.get((i+nodes.size()-1)%nodes.size()),b=nodes.get(i),c=nodes.get((i+1)%nodes.size());if((a.x==b.x&&b.x==c.x)||(a.y==b.y&&b.y==c.y))nodes.remove(i);}
   VectorDocument.Shape temp=new VectorDocument.Shape();temp.closed=true;temp.nodes.addAll(nodes);VectorDocument.simplify(temp,.55/scale);loops.add(temp.nodes);
  }
  int outer=0;double biggest=0;for(int i=0;i<loops.size();i++){double area=0;List<VectorDocument.Node> n=loops.get(i);for(int j=0,k=n.size()-1;j<n.size();k=j++)area+=n.get(k).x*n.get(j).y-n.get(j).x*n.get(k).y;if(area>biggest){biggest=area;outer=i;}}
  VectorDocument.Shape shape=new VectorDocument.Shape();shape.closed=true;shape.fill=color;shape.strokeWidth=0;shape.name="Bucket region";shape.nodes.addAll(loops.remove(outer));shape.contours.addAll(loops);shape.recenter();return shape;
 }
}
