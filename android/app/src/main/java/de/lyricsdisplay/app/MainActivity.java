package de.lyricsdisplay.app;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;
import de.lyricsdisplay.app.plugin.LyricsEnginePlugin;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(LyricsEnginePlugin.class);
        super.onCreate(savedInstanceState);
    }
}
