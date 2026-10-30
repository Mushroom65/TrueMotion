package app.truemotion.vector;
import android.app.*;
import android.graphics.*;
import android.net.Uri;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;
/** Sprite sheet export; ZIP contains real transparent PNG pages and Sparrow XML. */
final class VectorSprites {
    static final class Options {String name;int first,last,edge;}
    static Options pending;
    static void dialog(VectorEditor editor){editor.stop();editor.stage.finishPen();LinearLayout box=editor.box();EditText name=editor.field(box,L10n.t("Имя анимации"),"animation",false),first=editor.field(box,L10n.t("Первый кадр (от 1)"),"1",true),last=editor.field(box,L10n.t("Последний кадр"),String.valueOf(editor.doc.frames),true),edge=editor.field(box,L10n.t("Максимальная сторона спрайта (пиксели)"),String.valueOf(Math.min(512,Math.max(editor.doc.width,editor.doc.height))),true);TextView help=new TextView(editor.activity);help.setText(L10n.t("ZIP содержит PNG + XML TextureAtlas/Sparrow. Большая анимация делится на страницы. Прозрачность и положение кадра сохраняются."));box.addView(help);editor.form(L10n.t("Экспорт спрайтов PNG + XML"),box,()->{Options options=new Options();options.name=name.getText().toString().trim().replaceAll("[^\\p{L}\\p{N}_-]","_");if(options.name.isEmpty())throw new IllegalArgumentException(L10n.t("Введите имя"));options.first=(int)editor.number(first,1,editor.doc.frames)-1;options.last=(int)editor.number(last,options.first+1,editor.doc.frames)-1;options.edge=(int)editor.number(edge,1,4096);pending=options;VectorFileActivity.open(editor,VectorFileActivity.EXPORT);});}
    static void write(Activity activity,Uri uri,VectorDocument document,Options options)throws Exception{
        double scale=options.edge/(double)Math.max(document.width,document.height);int w=Math.max(1,(int)Math.round(document.width*scale)),h=Math.max(1,(int)Math.round(document.height*scale));int columns=Math.max(1,2048/(w+2)),rows=Math.max(1,2048/(h+2)),capacity=columns*rows,total=options.last-options.first+1,page=0;VectorCanvas renderer=new VectorCanvas();
        try(OutputStream output=activity.getContentResolver().openOutputStream(uri,"w");ZipOutputStream zip=new ZipOutputStream(output)){for(int begin=0;begin<total;begin+=capacity){int count=Math.min(capacity,total-begin),usedCols=Math.min(columns,count),usedRows=(count+columns-1)/columns,pageW=usedCols*(w+2),pageH=usedRows*(h+2);WorkBudget.memory((long)pageW*pageH*8);Bitmap bitmap=Bitmap.createBitmap(pageW,pageH,Bitmap.Config.ARGB_8888);try{Canvas canvas=new Canvas(bitmap);String base=total<=capacity?options.name:options.name+String.format(Locale.US,"-%03d",++page);StringBuilder xml=new StringBuilder(VectorAtlas.header(base+".png",document.fps));for(int i=0;i<count;i++){int frame=options.first+begin+i,x=(i%columns)*(w+2)+1,y=(i/columns)*(h+2)+1;canvas.save();canvas.clipRect(x,y,x+w,y+h);canvas.translate(x,y);canvas.scale((float)(w/(double)document.width),(float)(h/(double)document.height));renderer.draw(canvas,document,frame);canvas.restore();xml.append(VectorAtlas.entry(options.name+String.format(Locale.US,"%04d",frame-options.first),x,y,w,h));}xml.append("</TextureAtlas>\n");zip.putNextEntry(new ZipEntry(base+".png"));if(!bitmap.compress(Bitmap.CompressFormat.PNG,100,zip))throw new IOException("PNG encoding failed");zip.closeEntry();zip.putNextEntry(new ZipEntry(base+".xml"));zip.write(xml.toString().getBytes("UTF-8"));zip.closeEntry();}finally{bitmap.recycle();}}}finally{renderer.clear();}
    }
}
