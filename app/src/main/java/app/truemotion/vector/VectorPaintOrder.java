package app.truemotion.vector;
import java.util.*;
/** Paint fills above existing colour while retaining the original editable stroke on top. */
public final class VectorPaintOrder {
 static List<VectorDocument.Shape> outlines(List<VectorDocument.Shape> shapes){boolean bucket=false;for(VectorDocument.Shape s:shapes)bucket|=s.bucketFill;ArrayList<VectorDocument.Shape> result=new ArrayList<VectorDocument.Shape>();if(bucket)for(VectorDocument.Shape s:shapes)if(!s.bucketFill&&!s.isImage()&&!s.isText()&&!s.isSymbol()&&s.strokeWidth>0&&(s.stroke>>>24)>0){VectorDocument.Shape copy=s.copy();copy.fill=0;result.add(copy);}return result;}
 public static List<VectorDocument.Shape> drawing(List<VectorDocument.Shape> shapes){boolean bucket=false;for(VectorDocument.Shape s:shapes)bucket|=s.bucketFill;if(!bucket)return shapes;ArrayList<VectorDocument.Shape> result=new ArrayList<VectorDocument.Shape>(shapes);result.addAll(outlines(shapes));return result;}
}
