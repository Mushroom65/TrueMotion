package app.truemotion.vector;
import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.widget.Button;
import java.io.*;
import java.util.*;
/** Real SVG assets, rendered as vectors at the device density. */
final class VectorIcon extends Drawable {
    static final Map<String,VectorDocument> cache=new HashMap<String,VectorDocument>();
    final int intrinsic;final VectorDocument document;final VectorCanvas renderer=new VectorCanvas();int alpha=255;
    VectorIcon(Context context,String name){intrinsic=Math.round(24*context.getResources().getDisplayMetrics().density);VectorDocument loaded=cache.get(name);if(loaded==null)try(InputStream input=context.getAssets().open("lab-vector-icons/"+name+".svg")){loaded=VectorSvg.read(input).document;cache.put(name,loaded);}catch(Exception error){throw new IllegalArgumentException("SVG icon: "+name,error);}document=loaded;}
    @Override public void draw(Canvas canvas){Rect bounds=getBounds();int count=canvas.saveLayerAlpha(new RectF(bounds),alpha);canvas.translate(bounds.left,bounds.top);canvas.scale(bounds.width()/(float)document.width,bounds.height()/(float)document.height);renderer.draw(canvas,document,0);canvas.restoreToCount(count);}
    @Override public void setAlpha(int alpha){this.alpha=alpha;invalidateSelf();}
    @Override public void setColorFilter(ColorFilter filter){}
    @Override public int getIntrinsicWidth(){return intrinsic;}
    @Override public int getIntrinsicHeight(){return intrinsic;}
    @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    static boolean install(Button button,String title){String[] titles={"V","A","P","✎","B","╱","□","▢","○","☆","⬡","⌁","▣","◉","⌾","×","T","☞","＋","Y","↶","↷","▶","Ⅱ","|‹","‹","›","⋮"};String[] names={"select","nodes","pen","pencil","brush","line","rectangle","round-rectangle","ellipse","star","polygon","lasso","fill","stroke","eyedropper","erase","text","hand","zoom","bone","undo","redo","play","pause","first","back","next","menu"};for(int i=0;i<titles.length;i++)if(titles[i].equals(title)){VectorIcon icon=new VectorIcon(button.getContext(),names[i]);int size=(int)(24*button.getResources().getDisplayMetrics().density+.5f);icon.setBounds(0,0,size,size);button.setCompoundDrawables(icon,null,null,null);button.setText("");button.setContentDescription(title);return true;}return false;}
}
