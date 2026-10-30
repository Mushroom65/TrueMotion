package app.truemotion.vector;
import android.content.Context;
import android.graphics.*;
import android.net.Uri;
import android.util.Base64;
import java.io.*;
import java.util.*;
import java.security.MessageDigest;

/** Embedded atlas pages, decoded on demand; persistent cropped sources for the native engine. */
final class VectorRaster {
    static VectorImage image(byte[] png)throws Exception{BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(png,0,png.length,options);if(options.outWidth<1||options.outHeight<1||options.outWidth>16384||options.outHeight>16384)throw new IOException(L10n.t("Не удалось прочитать PNG-атлас"));VectorImage image=new VectorImage();image.width=options.outWidth;image.height=options.outHeight;image.png=Base64.encodeToString(png,Base64.NO_WRAP);return image;}
    static Bitmap decode(VectorImage image)throws Exception{WorkBudget.memory((long)image.width*image.height*4+image.png.length()*3L);byte[] png=Base64.decode(image.png,Base64.DEFAULT);Bitmap bitmap=BitmapFactory.decodeByteArray(png,0,png.length);if(bitmap==null||bitmap.getWidth()!=image.width||bitmap.getHeight()!=image.height)throw new IOException(L10n.t("Не удалось прочитать PNG-атлас"));return bitmap;}
    static void prepare(Context context,VectorDocument doc)throws Exception{if(doc.images.isEmpty())return;File directory=new File(context.getFilesDir(),"media/truevector-images");directory.mkdirs();ArrayList<VectorDocument> docs=new ArrayList<VectorDocument>();docs.add(doc);for(VectorSymbol symbol:doc.symbols.values())docs.add(symbol.document);for(VectorImage image:doc.images.values()){Bitmap bitmap=null;try{for(VectorDocument current:docs)for(VectorDocument.Track track:current.tracks)for(VectorDocument.Cel cel:track.keys.values())for(VectorDocument.Shape shape:cel.shapes)if(shape.image.equals(image.id)){String key=image.id+Arrays.toString(shape.imageRect);byte[] hash=MessageDigest.getInstance("SHA-256").digest(key.getBytes("UTF-8"));StringBuilder name=new StringBuilder();for(byte b:hash)name.append(String.format(Locale.US,"%02x",b&255));File file=new File(directory,name+".png");if(!file.isFile()){if(bitmap==null)bitmap=decode(image);int[] r=shape.imageRect;Bitmap crop=Bitmap.createBitmap(bitmap,r[0],r[1],r[2],r[3]);try(OutputStream output=new FileOutputStream(file)){if(!crop.compress(Bitmap.CompressFormat.PNG,100,output))throw new IOException("PNG encoding failed");}finally{if(crop!=bitmap)crop.recycle();}}shape.imageUri=Uri.fromFile(file).toString();}}finally{if(bitmap!=null)bitmap.recycle();}}}
}
