package app.truemotion.vector;
import android.app.Activity;
import android.app.AlertDialog;
final class MediaWork {
    static void showError(Activity activity,Throwable error) {
        new AlertDialog.Builder(activity).setTitle("True Motion").setMessage(Bridge.message(error)).setPositiveButton("OK",null).show();
    }
}
