package app.truemotion.vector;

import android.content.Context;
import android.util.Log;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.util.Locale;
import org.json.JSONObject;
import org.json.JSONArray;

/** Translations for Lab UI only; scene IDs, property keys and user names stay intact. */
public final class L10n {
    private static WeakReference<Context> current=new WeakReference<Context>(null);
    private static volatile JSONObject catalog;
    private L10n(){}
    public static void use(Context context){if(context!=null)current=new WeakReference<Context>(context);}
    private static Context context(){
        Context context=current.get();
        
        return context;
    }
    public static String t(String source){
        Context context=context();
        Locale locale=context==null?Locale.getDefault():context.getResources().getConfiguration().locale;
        String language=locale.getLanguage();
        if("ru".equals(language))return source;
        try{
            if(catalog==null&&context!=null)synchronized(L10n.class){
                if(catalog==null)try(InputStream in=context.getAssets().open("lab_strings.json")){
                    ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[8192];int count;
                    while((count=in.read(buffer))!=-1)out.write(buffer,0,count);
                    catalog=new JSONObject(new String(out.toByteArray(),"UTF-8"));
                }
            }
            JSONArray pair=catalog==null?null:catalog.optJSONArray(source);
            if(pair!=null)return pair.getString("zh".equals(language)?1:0);
        }catch(Exception e){Log.e("TrueMotionLocale","Translation catalog",e);}
        return source;
    }
}
