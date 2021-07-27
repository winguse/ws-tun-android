package com.winguse.websocketvpn;

import androidx.appcompat.app.AppCompatActivity;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.VpnService;
import android.os.Bundle;
import android.widget.TextView;

public class MainActivity extends AppCompatActivity {

    public interface Prefs {
        String NAME = "WebSocketVPN";
        String SERVER_URL = "server.url";
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        final TextView serverURL = findViewById(R.id.ServerURL);
        final SharedPreferences prefs = getSharedPreferences(Prefs.NAME, MODE_PRIVATE);

        serverURL.setText(prefs.getString(Prefs.SERVER_URL, ""));

        findViewById(R.id.buttonConnect).setOnClickListener(v -> {
            prefs.edit()
                    .putString(Prefs.SERVER_URL, serverURL.getText().toString())
                    .commit();
            Intent intent = VpnService.prepare(MainActivity.this);
            if (intent != null) {
                startActivityForResult(intent, 0);
            } else {
                onActivityResult(0, RESULT_OK, null);
            }
        });
        findViewById(R.id.buttonDisconnect).setOnClickListener(v -> {
            startService(getServiceIntent().setAction(WebSocketVpnService.ACTION_DISCONNECT));
        });
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result == RESULT_OK) {
            startService(getServiceIntent().setAction(WebSocketVpnService.ACTION_CONNECT));
        }
    }

    private Intent getServiceIntent() {
        return new Intent(this, WebSocketVpnService.class);
    }
}