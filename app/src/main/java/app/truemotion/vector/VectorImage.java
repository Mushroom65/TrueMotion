package app.truemotion.vector;
import org.json.*;
import java.util.*;
/** Embedded PNG atlas page. URI is regenerated on Android and never authoritative. */
public final class VectorImage {
    public String id=UUID.randomUUID().toString(),png="";public int width,height;
    JSONObject json()throws JSONException{return new JSONObject().put("id",id).put("png",png).put("width",width).put("height",height);}
    static VectorImage read(JSONObject value)throws JSONException{VectorImage image=new VectorImage();image.id=value.getString("id");image.png=value.getString("png");image.width=value.getInt("width");image.height=value.getInt("height");return image;}
}
