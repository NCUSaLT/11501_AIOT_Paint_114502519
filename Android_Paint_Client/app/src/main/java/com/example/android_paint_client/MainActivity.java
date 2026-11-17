package com.example.android_paint_client;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private EditText etClientName, etServerIp, etServerPort;
    private Button btnJoin;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etClientName = findViewById(R.id.etClientName);
        etServerIp = findViewById(R.id.etServerIp);
        etServerPort = findViewById(R.id.etServerPort);
        btnJoin = findViewById(R.id.btnJoin);

        btnJoin.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String name = etClientName.getText().toString().replace("|", "").trim();
                String ip = etServerIp.getText().toString().trim();
                String portStr = etServerPort.getText().toString().trim();

                if (name.isEmpty() || ip.isEmpty() || portStr.isEmpty()) {
                    Toast.makeText(MainActivity.this, "請填寫完整資訊", Toast.LENGTH_SHORT).show();
                    return;
                }

                int port;
                try {
                    port = Integer.parseInt(portStr);
                } catch (NumberFormatException e) {
                    Toast.makeText(MainActivity.this, "Port 請輸入數字", Toast.LENGTH_SHORT).show();
                    return;
                }
                if (port < 1 || port > 65535) {
                    Toast.makeText(MainActivity.this, "Port 範圍為 1 ~ 65535", Toast.LENGTH_SHORT).show();
                    return;
                }

                Intent intent = new Intent(MainActivity.this, PaintClientActivity.class);
                intent.putExtra("CLIENT_NAME", name);
                intent.putExtra("SERVER_IP", ip);
                intent.putExtra("SERVER_PORT", port);
                startActivity(intent);
            }
        });
    }
}