package app.truemotion.vector;
import android.graphics.*;
import java.util.*;
/** Canvas renderer shared by editor preview and sprite export. */
final class VectorCanvas {
    Runnable redraw;
    final Paint paint=new Paint(3);final LinkedHashMap<String,Bitmap> pages=new LinkedHashMap<String,Bitmap>(4,.75f,true);
    static Path path(VectorDocument.Shape shape){Path path=new Path();add(path,shape.nodes,shape.closed);for(ArrayList<VectorDocument.Node> contour:shape.contours)add(path,contour,true);return path;}
    static void add(Path path,List<VectorDocument.Node> nodes,boolean closed){if(nodes.isEmpty())return;VectorDocument.Node first=nodes.get(0);path.moveTo((float)first.x,(float)first.y);int count=closed?nodes.size():nodes.size()-1;for(int i=0;i<count;i++){VectorDocument.Node a=nodes.get(i),b=nodes.get((i+1)%nodes.size());path.cubicTo((float)(a.x+a.ox),(float)(a.y+a.oy),(float)(b.x+b.ix),(float)(b.y+b.iy),(float)b.x,(float)b.y);}if(closed)path.close();}
    void draw(Canvas canvas,VectorDocument doc,int frame){for(VectorDocument.Shape shape:doc.poseFrame(frame))draw(canvas,shape,0,doc,frame,0);}
    void draw(Canvas c,VectorDocument.Shape s,int tint,VectorDocument owner,int frame,int depth){if(depth>32)return;c.save();c.translate((float)s.x,(float)s.y);c.rotate((float)s.angle);c.scale((float)s.sx,(float)s.sy);
        if(s.isMedia()){VectorMedia.draw(this,c,s,owner,frame,tint);c.restore();return;}
        if(s.isSymbol()){VectorSymbol asset=owner.symbols.get(s.symbol);if(asset!=null){c.translate(-asset.document.width/2f,-asset.document.height/2f);int layer=c.saveLayerAlpha(null,(int)(s.opacity*255));for(VectorDocument.Shape child:asset.document.poseFrame(owner.symbolFrame(s,frame)))draw(c,child,tint,asset.document,owner.symbolFrame(s,frame),depth+1);c.restoreToCount(layer);}c.restore();return;}
        paint.setShader(null);paint.setStyle(Paint.Style.FILL);int color=tint==0?s.fill:tint;paint.setColor(color);paint.setAlpha((int)((color>>>24)*(tint==0?s.opacity:.30)));
        if(s.isImage()){try{Bitmap bitmap=pages.get(s.image);if(bitmap==null){VectorImage source=owner.images.get(s.image);if(source==null)throw new IllegalArgumentException("Missing atlas image");bitmap=VectorRaster.decode(source);while(pages.size()>=3){String id=pages.keySet().iterator().next();pages.remove(id).recycle();}pages.put(s.image,bitmap);}int[] r=s.imageRect,f=s.imageFrame;Rect rect=new Rect(r[0],r[1],r[0]+r[2],r[1]+r[3]);float x=-f[2]-f[0]/2f,y=-f[3]-f[1]/2f;paint.setColor(Color.WHITE);paint.setAlpha((int)(s.opacity*255));paint.setFilterBitmap(false);c.drawBitmap(bitmap,rect,new RectF(x,y,x+r[2],y+r[3]),paint);}catch(Exception error){c.restore();throw new IllegalArgumentException(error);}c.restore();return;}
        if(s.isText()){paint.setTypeface(Typeface.DEFAULT);paint.setTextSize((float)s.fontSize);paint.setTextAlign(Paint.Align.CENTER);String[] lines=s.text.split("\n",-1);for(int i=0;i<lines.length;i++)c.drawText(lines[i],0,(float)((i-(lines.length-1)/2d)*s.fontSize*1.2+s.fontSize*.35),paint);}else{Path path=path(s);if((s.fill>>>24)>0)c.drawPath(path,paint);if(s.strokeWidth>0&&(s.stroke>>>24)>0){paint.setStyle(Paint.Style.STROKE);paint.setStrokeCap(Paint.Cap.ROUND);paint.setStrokeJoin(Paint.Join.ROUND);paint.setStrokeWidth((float)s.strokeWidth);color=tint==0?s.stroke:tint;paint.setColor(color);paint.setAlpha((int)((color>>>24)*(tint==0?s.opacity:.3)));c.drawPath(path,paint);}}
        paint.setStyle(Paint.Style.FILL);paint.setAlpha(255);c.restore();
    }
    void clear(){for(Bitmap bitmap:pages.values())bitmap.recycle();pages.clear();}
}
