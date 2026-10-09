package net.xcds.iptv;

import android.app.Activity;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * What the apk carries, and under which licence, with the licence texts themselves.
 *
 * The texts are read out of assets/licenses/ at runtime rather than copied into a string
 * resource, so there is one copy of each in the apk and it is the same file the build
 * stages and prints in its verify block. LGPL and OFL attribution is normally done by
 * showing the licence, so this is the point of the screen rather than a nicety - and it
 * has to work offline, which reading from assets does.
 */
public class AboutActivity extends Activity {

    private static final String TAG = "iptv";
    private static final String DIR = "licenses/";

    /** apk file, heading. The order is the order they are shown in. */
    private static final String[][] LICENCES = {
            {"LICENSE", "This app - MIT"},
            {"LGPL-2.1.txt", "libVLC 3.6.5 - LGPL-2.1-or-later"},
            {"LLVM.txt", "libc++ / LLVM runtime - Apache-2.0 with LLVM exceptions"},
            {"OFL.txt", "Literata - SIL Open Font License 1.1"},
            {"THIRD-PARTY.md", "Third-party notes"},
    };

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Color.BLACK);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(24), dp(24), dp(24), dp(24));
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText(R.string.about_name);
        title.setTextColor(Color.WHITE);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        root.addView(title);

        TextView version = new TextView(this);
        version.setText(versionText());
        version.setTextColor(0xFF9E9E9E);
        version.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        version.setPadding(0, dp(4), 0, dp(12));
        root.addView(version);

        // The notice comes before the licences, because it is about the app rather than about the
        // code inside it, and because it is the sentence that must not be missed.
        TextView notice = new TextView(this);
        notice.setText(R.string.notice);
        notice.setTextColor(0xFFB0BEC5);
        notice.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        notice.setPadding(0, dp(12), 0, dp(12));
        root.addView(notice);

        // One row per component with its text behind a Show button, rather than every licence
        // in one scroll: the point of the screen is what the apk carries and under which
        // licence, and the LGPL text alone is a hundred screens long. Collapsed, the list is
        // scannable; expanded, the text is there in full, which is what the licences ask for.
        for (String[] licence : LICENCES) {
            final TextView body = new TextView(this);
            body.setText(read(licence[0]));
            body.setTextColor(0xFFB0BEC5);
            body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            body.setVisibility(View.GONE);

            final Button toggle = new Button(this);
            toggle.setText(licence[1]);
            toggle.setAllCaps(false);
            toggle.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    boolean showing = body.getVisibility() == View.VISIBLE;
                    body.setVisibility(showing ? View.GONE : View.VISIBLE);
                    toggle.setText(licence[1] + (showing ? "" : " - hide"));
                }
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMargins(0, dp(8), 0, 0);
            root.addView(toggle, params);
            root.addView(body);
        }

        setContentView(scroll);
    }

    /** "version 1.2 (3)" straight from the package, so it cannot drift from the manifest. */
    private String versionText() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return "version " + info.versionName + " (" + info.versionCode + ")";
        } catch (Exception e) {
            Log.i(TAG, "about: cannot read the package version (" + e + ")");
            return "version unknown";
        }
    }

    /** The staged licence text, or a note saying which file is missing. */
    private String read(String name) {
        try (InputStream in = getAssets().open(DIR + name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) > 0) {
                out.write(chunk, 0, read);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            // Worth saying out loud rather than showing an empty box: the licence being
            // absent from the apk is a packaging bug, not a display problem.
            Log.i(TAG, "about: assets/" + DIR + name + " is missing (" + e + ")");
            return "(assets/" + DIR + name + " is missing from this apk)";
        }
    }
}
