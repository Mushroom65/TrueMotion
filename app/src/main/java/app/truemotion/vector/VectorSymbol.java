package app.truemotion.vector;
import java.util.*;
import org.json.*;
public final class VectorSymbol {
    public String id=UUID.randomUUID().toString(),name;public VectorDocument document;
    public VectorSymbol(String title,VectorDocument d){name=title;document=d;}
    JSONObject json()throws JSONException{return new JSONObject().put("id",id).put("name",name).put("document",document.data(false));}
    static VectorSymbol read(JSONObject o)throws JSONException{VectorSymbol s=new VectorSymbol(o.getString("name"),VectorDocument.fromData(o.getJSONObject("document"),false));s.id=o.getString("id");return s;}
}
