package app.truemotion.vector;
import android.app.Activity;
import android.net.Uri;
import android.graphics.*;
import java.io.*;
final class MediaCompat {
    static boolean isGif(Activity activity,Uri uri)throws Exception {
        try(InputStream in=activity.getContentResolver().openInputStream(uri)){
            if(in==null)return false;byte[] head=new byte[6];int count=0,n;
            while(count<6&&(n=in.read(head,count,6-count))>0)count+=n;
            return count==6&&new String(head,"US-ASCII").startsWith("GIF8");
        }
    }
    static void vectorGif(Activity picker,VectorEditor editor,Uri uri,Runnable done)throws Exception {
        byte[] raw=VectorMediaIO.bytes(picker,uri,64*1024*1024);
        Movie movie=Movie.decodeByteArray(raw,0,raw.length);
        if(movie==null)throw new IOException("GIF decoding failed");
        int duration=Math.max(10,movie.duration()),w=movie.width(),h=movie.height();
        WorkBudget.memory((long)w*h*12+raw.length);
        VectorDocument document=new VectorDocument(w,h,editor.doc.fps,Math.max(1,(int)Math.ceil(duration*editor.doc.fps/1000)));
        document.tracks.get(0).keys.clear();
        Bitmap frame=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
        try {
            Canvas canvas=new Canvas(frame);
            for(int i=0;i<document.frames;i++) {
                canvas.drawColor(Color.TRANSPARENT,PorterDuff.Mode.CLEAR);
                movie.setTime((int)(i*1000/document.fps));movie.draw(canvas,0,0);
                ByteArrayOutputStream png=new ByteArrayOutputStream();frame.compress(Bitmap.CompressFormat.PNG,100,png);
                WorkBudget.memory(png.size()*6L+65536);
                VectorImage image=VectorRaster.image(png.toByteArray());document.images.put(image.id,image);
                VectorDocument.Shape shape=new VectorDocument.Shape();shape.image=image.id;shape.name="GIF";
                shape.imageRect=new int[]{0,0,w,h};shape.imageFrame=new int[]{w,h,0,0};shape.x=w/2d;shape.y=h/2d;
                VectorDocument.Cel cel=new VectorDocument.Cel();cel.shapes.add(shape);document.tracks.get(0).keys.put(i,cel);
            }
        } finally {frame.recycle();}
        picker.runOnUiThread(()->{
            done.run();editor.snapshot();editor.doc.images.putAll(document.images);
            VectorSymbol symbol=new VectorSymbol("GIF",document);editor.doc.symbols.put(symbol.id,symbol);
            for(VectorSymbol asset:editor.doc.symbols.values()){asset.document.images=editor.doc.images;asset.document.symbols=editor.doc.symbols;}
            VectorDocument.Shape shape=new VectorDocument.Shape();shape.name="GIF";shape.symbol=symbol.id;
            shape.symbolStart=editor.doc.time(editor.frame);shape.x=editor.doc.width/2d;shape.y=editor.doc.height/2d;
            shape.sx=shape.sy=Math.min(1,Math.min(editor.doc.width/(double)w,editor.doc.height/(double)h));
            editor.edit().shapes.add(shape);editor.doc.frames=Math.max(editor.doc.frames,editor.frame+document.frames);
            editor.selected.clear();editor.selected.add(shape.id);editor.stage.renderer.clear();editor.refresh();
        });
    }
}
