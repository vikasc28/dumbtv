package tv.dumbtv.home;

import android.app.Activity;
import android.media.tv.TvContract;
import android.media.tv.TvView;
import android.os.Bundle;
import android.util.Log;
import android.view.WindowManager;
import android.widget.Toast;

/**
 * Shows an HDMI or AV input full screen in a TvView. Fire OS answers no app's
 * ACTION_VIEW for a passthrough channel, and its own switcher needs the
 * signature|amazon TUNE_INPUT permission, so playing the input here is the open route.
 */
public final class Watch extends Activity {
    static final String INPUT = "input";
    private TvView view;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        String id = getIntent().getStringExtra(INPUT);
        if (id == null) {
            finish();
            return;
        }
        view = new TvView(this);
        view.setCallback(new TvView.TvInputCallback() {
            @Override
            public void onConnectionFailed(String input) {
                fail("connection failed: " + input);
            }

            @Override
            public void onDisconnected(String input) {
                fail("disconnected: " + input);
            }
        });
        setContentView(view);
        view.tune(id, TvContract.buildChannelUriForPassthroughInput(id));
    }

    private void fail(String why) {
        Log.w("dumbtv", "watch " + why);
        Toast.makeText(this, "Could not open that input", Toast.LENGTH_SHORT).show();
        finish();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (view != null) view.reset();
        finish();
    }
}
