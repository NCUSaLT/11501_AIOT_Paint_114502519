package com.example.android_paint_server;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private EditText etServerName, etServerPort;
    private Button btnStartServer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etServerName = findViewById(R.id.etServerName);
        etServerPort = findViewById(R.id.etServerPort);
        btnStartServer = findViewById(R.id.btnStartServer);

        btnStartServer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String name = etServerName.getText().toString().replace("|", "").trim();
                if (name.isEmpty()) {
                    name = "Server";
                }

                int port;
                try {
                    port = Integer.parseInt(etServerPort.getText().toString().trim());
                } catch (NumberFormatException e) {
                    Toast.makeText(MainActivity.this, "Port 請輸入數字", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (port < 1024 || port > 65535) {
                    Toast.makeText(MainActivity.this, "Port 請輸入 1024 ~ 65535", Toast.LENGTH_SHORT).show();
                    return;
                }

                Intent intent = new Intent(MainActivity.this, PaintServerActivity.class);
                intent.putExtra("SERVER_NAME", name);
                intent.putExtra("SERVER_PORT", port);
                startActivity(intent);
            }
        });
    }
}