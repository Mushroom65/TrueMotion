package app.truemotion.vector;
import android.app.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import android.text.*;
import java.util.*;

/** Touch HSV picker, alpha, hex, preset and recent swatches. */
final class VectorColorDialog {
    static void show(VectorEditor editor,boolean line){new VectorColorDialog(editor,line).show();}
    final VectorEditor e;final boolean line;final float[] hsv=new float[3];int alpha;boolean updating;
    LinearLayout box;EditText hex;TextView preview;SeekBar hue,opacity;Square square;
    VectorColorDialog(VectorEditor editor,boolean stroke){e=editor;line=stroke;int color=line?e.stroke:e.fill;Color.colorToHSV(color,hsv);alpha=Color.alpha(color);}
    int color(){return Color.HSVToColor(alpha,hsv);}
    void set(int color){Color.colorToHSV(color,hsv);alpha=Color.alpha(color);update();}
    void update(){updating=true;hex.setText(VectorDocument.hex(color()));preview.setBackgroundColor(color());hue.setProgress((int)hsv[0]);opacity.setProgress(alpha);square.invalidate();updating=false;}
    void show(){box=e.box();square=new Square();box.addView(square,new LinearLayout.LayoutParams(-1,e.dp(180)));
        TextView h=new TextView(e.activity);h.setText(L10n.t("Цветовой тон"));box.addView(h);hue=new SeekBar(e.activity);hue.setMax(359);GradientDrawable rainbow=new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,new int[]{Color.RED,Color.YELLOW,Color.GREEN,Color.CYAN,Color.BLUE,Color.MAGENTA,Color.RED});rainbow.setCornerRadius(e.dp(5));hue.setBackground(rainbow);box.addView(hue,new LinearLayout.LayoutParams(-1,e.dp(32)));
        TextView a=new TextView(e.activity);a.setText(L10n.t("Непрозрачность"));box.addView(a);opacity=new SeekBar(e.activity);opacity.setMax(255);box.addView(opacity,new LinearLayout.LayoutParams(-1,e.dp(32)));
        LinearLayout row=e.row();preview=new TextView(e.activity);preview.setText(" ");row.addView(preview,new LinearLayout.LayoutParams(e.dp(50),e.dp(40)));hex=new EditText(e.activity);hex.setSingleLine(true);hex.setHint("#RRGGBB / #AARRGGBB");row.addView(hex,new LinearLayout.LayoutParams(0,-2,1));box.addView(row);
        int[] presets={0,0xffffffff,0xff000000,0xffff5570,0xffff8833,0xffffd166,0xff62d8a8,0xff3ad4c4,0xff54b8ff,0xff5c73ff,0xffa18dff,0xffff75cb};swatches(presets);
        String recent=e.activity.getSharedPreferences("truevector",0).getString("colors","");ArrayList<Integer> colors=new ArrayList<Integer>();for(String item:recent.split(","))try{colors.add(Color.parseColor(item));}catch(Exception ignored){}if(!colors.isEmpty()){TextView t=new TextView(e.activity);t.setText(L10n.t("Недавние цвета"));box.addView(t);int[] values=new int[colors.size()];for(int i=0;i<values.length;i++)values[i]=colors.get(i);swatches(values);}
        hue.setOnSeekBarChangeListener(listener(true));opacity.setOnSeekBarChangeListener(listener(false));hex.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int a,int b,int c){}public void onTextChanged(CharSequence s,int a,int b,int c){if(updating)return;try{int color=Color.parseColor(s.toString().trim());Color.colorToHSV(color,hsv);alpha=Color.alpha(color);updating=true;preview.setBackgroundColor(color);hue.setProgress((int)hsv[0]);opacity.setProgress(alpha);square.invalidate();updating=false;hex.setError(null);}catch(Exception ignored){}}public void afterTextChanged(Editable s){}});update();
        e.form(line?L10n.t("Цвет обводки"):L10n.t("Цвет заливки"),box,()->{int value=Color.parseColor(hex.getText().toString().trim());e.applyColor(line,value);LinkedHashSet<String> saved=new LinkedHashSet<String>();saved.add(VectorDocument.hex(value));for(String old:recent.split(","))if(!old.isEmpty()&&saved.size()<12)saved.add(old);e.activity.getSharedPreferences("truevector",0).edit().putString("colors",android.text.TextUtils.join(",",saved)).apply();});
    }
    SeekBar.OnSeekBarChangeListener listener(final boolean h){return new SeekBar.OnSeekBarChangeListener(){public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){}public void onProgressChanged(SeekBar s,int value,boolean user){if(!user||updating)return;if(h)hsv[0]=value;else alpha=value;update();}};}
    void swatches(int[] colors){LinearLayout r=null;for(int i=0;i<colors.length;i++){if(i%6==0){r=e.row();box.addView(r);}final int color=colors[i];Button b=new Button(e.activity);GradientDrawable drawable=new GradientDrawable();drawable.setColor(color);drawable.setStroke(e.dp(1),0xff999999);drawable.setCornerRadius(e.dp(5));b.setBackground(drawable);b.setContentDescription(color==0?L10n.t("Прозрачный"):VectorDocument.hex(color));b.setOnClickListener(v->set(color));LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(0,e.dp(32),1);params.setMargins(e.dp(3),e.dp(3),e.dp(3),e.dp(3));r.addView(b,params);}}
    final class Square extends View {
        final Paint p=new Paint(3);Square(){super(e.activity);setContentDescription(L10n.t("Насыщенность и яркость"));}
        @Override protected void onDraw(Canvas c){float w=getWidth(),h=getHeight();p.setShader(new LinearGradient(0,0,w,0,Color.WHITE,Color.HSVToColor(new float[]{hsv[0],1,1}),Shader.TileMode.CLAMP));c.drawRect(0,0,w,h,p);p.setShader(new LinearGradient(0,0,0,h,0x00000000,Color.BLACK,Shader.TileMode.CLAMP));c.drawRect(0,0,w,h,p);p.setShader(null);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(e.dp(2));p.setColor(Color.WHITE);c.drawCircle(hsv[1]*w,(1-hsv[2])*h,e.dp(6),p);p.setStyle(Paint.Style.FILL);}
        @Override public boolean onTouchEvent(MotionEvent event){if(event.getActionMasked()==MotionEvent.ACTION_DOWN||event.getActionMasked()==MotionEvent.ACTION_MOVE){hsv[1]=Math.max(0,Math.min(1,event.getX()/getWidth()));hsv[2]=1-Math.max(0,Math.min(1,event.getY()/getHeight()));update();return true;}return true;}
    }
}
