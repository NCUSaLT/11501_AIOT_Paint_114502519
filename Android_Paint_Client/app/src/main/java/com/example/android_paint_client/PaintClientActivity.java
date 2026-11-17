package com.example.android_paint_client;

import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * 通訊協定請看 Server 端 PaintServerActivity 上方的說明。
 *
 * 執行緒設計：
 *   - 一條「連線 + 讀取」執行緒：connect、readLine、解析 Server 訊息。
 *   - 一條單執行緒「寫出佇列」(sendExecutor)：所有送出的訊息依序寫出，
 *     不會在 UI 執行緒做網路操作，筆跡的順序也不會亂。
 *   - 更新畫面一律回到 UI 執行緒。
 */
public class PaintClientActivity extends AppCompatActivity {

    private TextView tvClientStatus, tvNotification;
    private ScrollView svNotification;
    private ClientCanvasView clientCanvas;
    private View viewUserColor;
    private Button btnUndo, btnEraser, btnClear, btnLeave;

    private volatile String clientName;
    private String serverIp;
    private int serverPort;

    private volatile Socket socket;
    private volatile BufferedWriter writer;
    private volatile boolean running = true;
    private volatile String endReason = "與伺服器的連線已中斷";
    private boolean eraserOn = false;

    private final ExecutorService sendExecutor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_paint_client);

        tvClientStatus = findViewById(R.id.tvClientStatus);
        tvNotification = findViewById(R.id.tvNotification);
        svNotification = findViewById(R.id.svNotification);
        clientCanvas = findViewById(R.id.clientCanvas);
        viewUserColor = findViewById(R.id.viewUserColor);
        btnUndo = findViewById(R.id.btnUndo);
        btnEraser = findViewById(R.id.btnEraser);
        btnClear = findViewById(R.id.btnClear);
        btnLeave = findViewById(R.id.btnLeave);

        clientName = getIntent().getStringExtra("CLIENT_NAME");
        serverIp = getIntent().getStringExtra("SERVER_IP");
        serverPort = getIntent().getIntExtra("SERVER_PORT", 8080);

        tvClientStatus.setText("我: " + clientName + "\nServer: " + serverIp + ":" + serverPort);

        clientCanvas.setMyName(clientName);

        clientCanvas.setOnDrawListener(new ClientCanvasView.OnDrawListener() {
            @Override
            public void onDrawSend(int strokeId, float x1, float y1, float x2, float y2) {
                send("DRAW|" + strokeId + "|" + x1 + "|" + y1 + "|" + x2 + "|" + y2);
            }
        });

        // 橡皮擦模式：碰到自己的某一筆 -> 整筆刪除（別人的筆跡碰不到，Server 也會再檢查一次）
        clientCanvas.setOnEraseListener(new ClientCanvasView.OnEraseListener() {
            @Override
            public void onErase(int strokeId) {
                send("ERASE|" + strokeId);
            }
        });

        btnUndo.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                send("UNDO");
            }
        });

        btnEraser.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                eraserOn = !eraserOn;
                clientCanvas.setEraser(eraserOn);
                btnEraser.setSelected(eraserOn);
                btnEraser.setText(eraserOn ? "橡皮擦中" : "橡皮擦");
            }
        });

        btnClear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                send("CLEAR_MINE"); // 清除「自己」畫的所有筆跡
            }
        });

        btnLeave.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                leaveAndFinish();
            }
        });

        new Thread(new Runnable() {
            @Override
            public void run() {
                connectAndListen();
            }
        }).start();
    }

    // ------------------------------------------------------------------
    // 連線與接收
    // ------------------------------------------------------------------

    private void connectAndListen() {
        try {
            Socket s = new Socket();
            socket = s; // 先存起來，離開時才能中斷還在連線中的 Socket
            if (!running) {
                s.close();
                return;
            }
            s.connect(new InetSocketAddress(serverIp, serverPort), 5000);

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            writer = new BufferedWriter(
                    new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8));

            appendNotification("已連線至伺服器");
            send("JOIN|" + clientName);

            String line;
            while (running && (line = reader.readLine()) != null) {
                try {
                    if (!handleLine(line)) {
                        break; // 被踢出
                    }
                } catch (RuntimeException ignored) {
                    // 格式錯誤的訊息直接略過，不中斷連線
                }
            }
        } catch (Exception e) {
            if (running) {
                endReason = "連線失敗或中斷：" + e.getMessage();
            }
        } finally {
            onConnectionEnded();
        }
    }

    /** 處理一行 Server 訊息；回傳 false 代表要結束連線 */
    private boolean handleLine(String line) {
        if (line.startsWith("NOTICE|")) {
            appendNotification(line.substring(7));
            return true;
        }

        String[] p = line.split("\\|", -1);
        switch (p[0]) {
            case "WELCOME": {
                final String name = p[1];
                final int color = Integer.parseInt(p[2]);
                clientName = name;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        tvClientStatus.setText("我: " + name + "\nServer: " + serverIp + ":" + serverPort);
                        viewUserColor.setBackgroundColor(color);
                        clientCanvas.setMyName(name);
                    }
                });
                return true;
            }
            case "DRAW": {
                final String owner = p[1];
                final int strokeId = Integer.parseInt(p[2]);
                final float x1 = Float.parseFloat(p[3]);
                final float y1 = Float.parseFloat(p[4]);
                final float x2 = Float.parseFloat(p[5]);
                final float y2 = Float.parseFloat(p[6]);
                final int color = Integer.parseInt(p[7]);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        clientCanvas.addSegment(owner, strokeId, x1, y1, x2, y2, color);
                    }
                });
                return true;
            }
            case "UNDO": {
                final String owner = p[1];
                final int strokeId = Integer.parseInt(p[2]);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        clientCanvas.removeStroke(owner, strokeId);
                    }
                });
                return true;
            }
            case "CLEAR_USER": {
                final String owner = p[1];
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        clientCanvas.removeOwner(owner);
                    }
                });
                return true;
            }
            case "CLEAR_ALL":
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        clientCanvas.clearAll();
                    }
                });
                return true;
            case "KICKED":
                endReason = "你已被管理員踢出畫布";
                appendNotification(endReason);
                return false;
            default:
                return true;
        }
    }

    // ------------------------------------------------------------------
    // 送出 / 離開
    // ------------------------------------------------------------------

    /** 放進寫出佇列，可在任何執行緒呼叫 */
    private void send(final String msg) {
        try {
            sendExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    writeLine(msg);
                }
            });
        } catch (RejectedExecutionException ignored) {
        }
    }

    private void writeLine(String msg) {
        BufferedWriter w = writer;
        if (w == null) return; // 還沒連上線
        try {
            w.write(msg);
            w.write('\n');
            w.flush();
        } catch (IOException e) {
            closeSocketQuietly(); // 讀取執行緒會因此結束並做後續處理
        }
    }

    /** 使用者按「離開」：先把 LEAVE 送出去，再關閉連線 */
    private void leaveAndFinish() {
        if (!running) {
            finish();
            return;
        }
        running = false;
        try {
            sendExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    writeLine("LEAVE");
                    closeSocketQuietly();
                }
            });
            sendExecutor.shutdown();
        } catch (RejectedExecutionException e) {
            closeSocketQuietly();
        }
        finish();
    }

    /** 連線結束（Server 關閉、被踢、網路中斷、連線失敗）：顯示原因後離開畫面 */
    private void onConnectionEnded() {
        if (!running) return; // 使用者自己離開，不需要再處理
        running = false;
        closeSocketQuietly();
        sendExecutor.shutdownNow();
        final String reason = endReason;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(getApplicationContext(), reason, Toast.LENGTH_LONG).show();
                finish();
            }
        });
    }

    private void closeSocketQuietly() {
        Socket s = socket;
        if (s != null) {
            try { s.close(); } catch (IOException ignored) {}
        }
    }

    private void appendNotification(final String text) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                tvNotification.append(text + "\n");
                svNotification.post(new Runnable() {
                    @Override
                    public void run() {
                        svNotification.fullScroll(View.FOCUS_DOWN);
                    }
                });
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (running) { // 不是從「離開」或連線結束流程走到這裡（例如直接按返回鍵）
            running = false;
            closeSocketQuietly();
            sendExecutor.shutdownNow();
        }
    }
}