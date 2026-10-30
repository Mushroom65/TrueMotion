package app.truemotion.vector;
import java.util.*;
/** Compute the tapped bounded vector face without a bitmap or canvas-size grid. */
final class VectorBucket {
 static VectorDocument.Shape region(VectorDocument doc,List<VectorDocument.Shape> shapes,double x,double y,int color){VectorDocument.Shape result=VectorFaces.fill(shapes,x,y,color);if(result!=null)result.name=L10n.t("Заливка");return result;}
}
