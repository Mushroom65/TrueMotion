package app.truemotion.vector;
import android.app.*;import android.os.*;
/** Retain unsaved vector work while the native activity recreates its landscape layout. */
final class VectorSession implements Application.ActivityLifecycleCallbacks {
 static boolean installed;static VectorEditor active,pending;
 static void attach(VectorEditor e){active=e;if(!installed){installed=true;e.activity.getApplication().registerActivityLifecycleCallbacks(new VectorSession());}}
 static void closed(VectorEditor e){if(e.intentionalClose&&active==e)active=e.parentEditor;}
 public void onActivityDestroyed(Activity a){if(active!=null&&active.activity==a&&a.isChangingConfigurations()&&!active.intentionalClose){pending=active;pending.stop();}}
 public void onActivityResumed(Activity a){if(pending!=null&&a.getClass().getName().equals(pending.activity.getClass().getName()))restore(a,0);}
 static void state(VectorEditor next,VectorEditor old){next.doc=old.doc;next.frame=old.frame;next.track=old.track;next.tool=old.tool;next.fill=old.fill;next.stroke=old.stroke;next.width=old.width;next.smoothing=old.smoothing;next.dirty=old.dirty;next.undo.addAll(old.undo);next.redo.addAll(old.redo);next.undoBytes=old.undoBytes;next.symbolContext=old.symbolContext;next.editorTitle=old.editorTitle;}
 static void restore(Activity a,int attempt){new Handler(Looper.getMainLooper()).postDelayed(()->{VectorEditor old=pending;if(old==null||a.isFinishing())return;try{java.util.ArrayList<VectorEditor> chain=new java.util.ArrayList<VectorEditor>();for(VectorEditor e=old;e!=null;e=e.parentEditor)chain.add(0,e);VectorEditor root=chain.get(0);Object holder=root.holder,layer=VectorAdapter.current(holder,root.original);if(layer==null||!VectorAnimation.isLayer(layer))throw new IllegalStateException("Vector layer is not ready");VectorEditor next=new VectorEditor(a,holder,layer,root.doc);state(next,root);pending=null;next.show();for(int i=1;i<chain.size();i++){next.workspace.editSymbol(chain.get(i).symbolContext);next=active;state(next,chain.get(i));next.refresh();}}catch(Exception e){if(attempt<8)restore(a,attempt+1);else{android.util.Log.e("TrueVector","Restore after rotation",e);MediaWork.showError(a,e);}}},attempt==0?200:300);}
 public void onActivityCreated(Activity a,Bundle b){}public void onActivityStarted(Activity a){}public void onActivityPaused(Activity a){}public void onActivityStopped(Activity a){}public void onActivitySaveInstanceState(Activity a,Bundle b){}
}
