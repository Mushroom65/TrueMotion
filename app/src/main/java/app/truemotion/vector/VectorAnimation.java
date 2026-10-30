package app.truemotion.vector;
import android.app.Activity;
import android.util.Base64;
import java.io.*;
import java.util.zip.*;
public final class VectorAnimation {
    static final String PREFIX="truevector:v1:";
    public static String marker(VectorDocument document)throws Exception {
        byte[] raw=document.json().getBytes("UTF-8");WorkBudget.memory(raw.length*4L);
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        try(GZIPOutputStream zip=new GZIPOutputStream(out)){zip.write(raw);}
        return PREFIX+Base64.encodeToString(out.toByteArray(),Base64.NO_WRAP);
    }
    public static VectorDocument decode(String marker)throws Exception {
        if(!marker.startsWith(PREFIX))throw new IOException("Not a vector animation layer");
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        try(GZIPInputStream input=new GZIPInputStream(new ByteArrayInputStream(Base64.decode(marker.substring(PREFIX.length()),Base64.DEFAULT)))){
            byte[] buffer=new byte[16384];int n;
            while((n=input.read(buffer))!=-1){if(out.size()+(long)n>64L*1024*1024)throw new IOException("Vector document is too large");out.write(buffer,0,n);}
        }
        return VectorDocument.read(out.toString("UTF-8"));
    }
    static boolean isLayer(Object layer){return VectorAdapter.isVector(layer);}
    static void commit(Activity activity,Object holder,Object original,VectorDocument document)throws Exception {
        VectorAdapter.commit(activity,holder,original,document);
    }
}
