package com.example.android_paint_server;

import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * 通訊協定（一行一則訊息，欄位以 | 分隔）
 *
 * Client -> Server
 *   JOIN|name
 *   DRAW|strokeId|x1|y1|x2|y2                 座標為 0~1 的比例
 *   UNDO                                      撤銷自己最後一筆
 *   ERASE|strokeId                            橡皮擦：整筆刪除「自己」的某一筆
 *   CLEAR_MINE                                清除自己所有筆跡
 *   LEAVE
 *
 * Server -> Client
 *   WELCOME|name|color
 *   DRAW|owner|strokeId|x1|y1|x2|y2|color
 *   UNDO|owner|strokeId                       移除某人的某一筆（撤銷與橡皮擦共用）
 *   CLEAR_USER|owner
 *   CLEAR_ALL
 *   NOTICE|text
 *   KICKED
 *
 * 執行緒設計：
 *   - 所有「狀態」(clients、畫布歷史) 都只在 UI 執行緒讀寫，所以不需要鎖。
 *   - 每個 Client 一條讀取執行緒（只負責 readLine，然後丟回 UI 執行緒處理）。
 *   - 每個 Client 一條單執行緒寫出佇列（網路寫入絕不在 UI 執行緒做）。
 */
public class PaintServerActivity extends AppCompatActivity {

    private static final int[] USER_COLORS = {
            Color.RED,
            Color.BLUE,
            Color.MAGENTA,
            Color.DKGRAY,
            Color.parseColor("#FFA500"),
            Color.parseColor("#2E7D32"),
            Color.parseColor("#00838F"),
            Color.parseColor("#6D4C41")
    };

    private TextView tvServerStatus;
    private SharedCanvasView canvasView;
    private Button btnClearAll;
    private ListView lvClients;

    private String serverName;
    private int serverPort;
    private volatile ServerSocket serverSocket;
    private volatile boolean running = true;

    private final List<ClientHandler> clients = new ArrayList<>(); // 只在 UI 執行緒存取
    private int colorIndex = 0;                                    // 只在 UI 執行緒存取
    private ClientAdapter clientAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_paint_server);

        tvServerStatus = findViewById(R.id.tvServerStatus);
        canvasView = findViewById(R.id.canvasView);
        btnClearAll = findViewById(R.id.btnClearAll);
        lvClients = findViewById(R.id.lvClients);

        serverName = getIntent().getStringExtra("SERVER_NAME");
        if (serverName == null) serverName = "Server";
        serverPort = getIntent().getIntExtra("SERVER_PORT", 8080);

        tvServerStatus.setText("Server：" + serverName + "\nIP：" + getLocalIpAddresses(serverPort));

        clientAdapter = new ClientAdapter();
        lvClients.setAdapter(clientAdapter);

        btnClearAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                canvasView.clearAll();
                broadcast("CLEAR_ALL");
            }
        });

        startServer();
    }

    // ------------------------------------------------------------------
    // 啟動 Server / 接受連線
    // ------------------------------------------------------------------

    private void startServer() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                ServerSocket ss;
                try {
                    ss = new ServerSocket();
                    ss.setReuseAddress(true);                       // 一定要在 bind 之前
                    ss.bind(new InetSocketAddress(serverPort));
                } catch (IOException e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(getApplicationContext(),
                                    "無法啟動 Server，Port " + serverPort + " 可能被占用",
                                    Toast.LENGTH_LONG).show();
                            finish();
                        }
                    });
                    return;
                }

                serverSocket = ss;
                if (!running) { // Activity 在 bind 完成前就被關掉了
                    try { ss.close(); } catch (IOException ignored) {}
                    return;
                }

                while (running) {
                    try {
                        final Socket socket = ss.accept();
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                onClientConnected(socket);
                            }
                        });
                    } catch (IOException e) {
                        break; // ServerSocket 被關閉
                    }
                }
            }
        }).start();
    }

    private void onClientConnected(Socket socket) {
        if (!running) {
            try { socket.close(); } catch (IOException ignored) {}
            return;
        }
        try {
            int color = USER_COLORS[colorIndex % USER_COLORS.length];
            colorIndex++;
            ClientHandler handler = new ClientHandler(socket, color);
            clients.add(handler);
            handler.start();
            clientAdapter.notifyDataSetChanged();
        } catch (IOException e) {
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    // ------------------------------------------------------------------
    // 單一 Client 的連線
    // ------------------------------------------------------------------

    private class ClientHandler {
        final Socket socket;
        final BufferedReader reader;
        final BufferedWriter writer;
        final ExecutorService writeExecutor = Executors.newSingleThreadExecutor();
        final int color;
        final String ip;
        String name = null; // 收到 JOIN 之前為 null（只在 UI 執行緒存取）
        volatile boolean closed = false;

        ClientHandler(Socket socket, int color) throws IOException {
            this.socket = socket;
            this.color = color;
            this.ip = socket.getInetAddress().getHostAddress();
            this.reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            this.writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
        }

        void start() {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    readLoop();
                }
            }).start();
        }

        private void readLoop() {
            try {
                String line;
                while (!closed && (line = reader.readLine()) != null) {
                    final String msg = line;
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            onMessage(ClientHandler.this, msg);
                        }
                    });
                }
            } catch (IOException ignored) {
                // 連線中斷，往下走清理流程
            }
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    removeClient(ClientHandler.this);
                }
            });
        }

        /** 把訊息放進寫出佇列（可在任何執行緒呼叫，不會阻塞） */
        void send(final String msg) {
            if (closed) return;
            try {
                writeExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        if (!tryWrite(msg)) {
                            closeNow();
                        }
                    }
                });
            } catch (RejectedExecutionException ignored) {
            }
        }

        /** 送出最後一則訊息後關閉連線（踢人用） */
        void sendThenClose(final String msg) {
            if (closed) return;
            closed = true; // 之後的 send() 都會被忽略
            try {
                writeExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        tryWrite(msg);
                        closeSocketQuietly();
                    }
                });
                writeExecutor.shutdown();
            } catch (RejectedExecutionException e) {
                closeSocketQuietly();
            }
        }

        void closeNow() {
            closed = true;
            closeSocketQuietly();
            writeExecutor.shutdownNow();
        }

        private boolean tryWrite(String msg) {
            try {
                writer.write(msg);
                writer.write('\n');
                writer.flush();
                return true;
            } catch (IOException e) {
                return false;
            }
        }

        private void closeSocketQuietly() {
            try { socket.close(); } catch (IOException ignored) {}
        }

        String displayName() {
            return name != null ? name : "(連線中)";
        }
    }

    // ------------------------------------------------------------------
    // 訊息處理（全部在 UI 執行緒）
    // ------------------------------------------------------------------

    private void onMessage(ClientHandler c, String line) {
        if (!clients.contains(c)) return; // 已被移除的連線

        try {
            if (line.startsWith("JOIN|")) {
                if (c.name != null) return; // 已經加入過
                String name = uniqueName(sanitizeName(line.substring(5)), c);
                c.name = name;
                c.send("WELCOME|" + name + "|" + c.color);
                // 把目前畫布上的內容同步給新加入的人
                for (SharedCanvasView.Segment s : canvasView.getSegments()) {
                    c.send(drawMessage(s));
                }
                broadcast("NOTICE|" + name + " 加入了畫布");
                clientAdapter.notifyDataSetChanged();
                return;
            }

            if (c.name == null) return; // 還沒 JOIN 就不處理其他指令

            String[] p = line.split("\\|", -1);
            switch (p[0]) {
                case "DRAW": {
                    int strokeId = Integer.parseInt(p[1]);
                    float x1 = clamp01(Float.parseFloat(p[2]));
                    float y1 = clamp01(Float.parseFloat(p[3]));
                    float x2 = clamp01(Float.parseFloat(p[4]));
                    float y2 = clamp01(Float.parseFloat(p[5]));

                    SharedCanvasView.Segment seg =
                            new SharedCanvasView.Segment(c.name, strokeId, x1, y1, x2, y2, c.color);
                    canvasView.addSegment(seg);
                    broadcast(drawMessage(seg)); // 包含畫的人自己，大家畫面一致
                    break;
                }
                case "UNDO": {
                    List<SharedCanvasView.Segment> segs = canvasView.getSegments();
                    for (int i = segs.size() - 1; i >= 0; i--) {
                        if (segs.get(i).owner.equals(c.name)) {
                            int strokeId = segs.get(i).strokeId;
                            canvasView.removeStroke(c.name, strokeId);
                            broadcast("UNDO|" + c.name + "|" + strokeId);
                            break;
                        }
                    }
                    break;
                }
                case "ERASE": {
                    int strokeId = Integer.parseInt(p[1]);
                    if (canvasView.removeStroke(c.name, strokeId)) {
                        broadcast("UNDO|" + c.name + "|" + strokeId);
                    }
                    break;
                }
                case "CLEAR_MINE":
                    canvasView.removeOwner(c.name);
                    broadcast("CLEAR_USER|" + c.name);
                    break;
                case "LEAVE":
                    removeClient(c);
                    break;
                default:
                    break;
            }
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException ignored) {
            // 格式錯誤的訊息直接丟掉，不影響連線
        }
    }

    private String drawMessage(SharedCanvasView.Segment s) {
        return "DRAW|" + s.owner + "|" + s.strokeId + "|" + s.x1 + "|" + s.y1 + "|" + s.x2 + "|" + s.y2 + "|" + s.color;
    }

    /** Client 斷線或主動離開 */
    private void removeClient(ClientHandler c) {
        if (!clients.remove(c)) return; // 已處理過
        c.closeNow();
        afterClientLeft(c, " 離開了畫布");
    }

    /** 管理員踢人：先把 KICKED 送出去再關線 */
    private void kick(ClientHandler c) {
        if (!clients.remove(c)) return;
        c.sendThenClose("KICKED");
        afterClientLeft(c, " 被踢出了畫布");
    }

    private void afterClientLeft(ClientHandler c, String noticeSuffix) {
        if (c.name != null) {
            canvasView.removeOwner(c.name);
            broadcast("CLEAR_USER|" + c.name);
            broadcast("NOTICE|" + c.name + noticeSuffix);
        }
        clientAdapter.notifyDataSetChanged();
    }

    /** 廣播給所有「已加入」的 Client */
    private void broadcast(String msg) {
        for (ClientHandler c : clients) {
            if (c.name != null) {
                c.send(msg);
            }
        }
    }

    // ------------------------------------------------------------------
    // 小工具
    // ------------------------------------------------------------------

    private static float clamp01(float v) {
        if (Float.isNaN(v)) return 0f;
        return Math.max(0f, Math.min(1f, v));
    }

    private String sanitizeName(String raw) {
        String s = raw == null ? "" : raw.replace("|", "").replace("\r", "").replace("\n", "").trim();
        if (s.length() > 20) s = s.substring(0, 20);
        return s.isEmpty() ? "user" : s;
    }

    private String uniqueName(String base, ClientHandler self) {
        String name = base;
        int n = 2;
        while (isNameTaken(name, self)) {
            name = base + "_" + n;
            n++;
        }
        return name;
    }

    private boolean isNameTaken(String name, ClientHandler self) {
        for (ClientHandler c : clients) {
            if (c != self && name.equals(c.name)) return true;
        }
        return false;
    }

    private String getLocalIpAddresses(int port) {
        StringBuilder sb = new StringBuilder();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        if (sb.length() > 0) sb.append(" / ");
                        sb.append(addr.getHostAddress()).append(":").append(port);
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return sb.length() > 0 ? sb.toString() : "(無法取得 IP)";
    }

    // ------------------------------------------------------------------
    // 成員列表
    // ------------------------------------------------------------------

    private class ClientAdapter extends BaseAdapter {
        @Override
        public int getCount() { return clients.size(); }

        @Override
        public Object getItem(int position) { return clients.get(position); }

        @Override
        public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = getLayoutInflater().inflate(R.layout.item_client_row, parent, false);
            }
            final ClientHandler client = clients.get(position);

            TextView tvName = convertView.findViewById(R.id.tvItemName);
            TextView tvIp = convertView.findViewById(R.id.tvItemIp);
            View viewColor = convertView.findViewById(R.id.viewItemColor);
            Button btnClearUser = convertView.findViewById(R.id.btnClearUser);
            Button btnKick = convertView.findViewById(R.id.btnKick);

            tvName.setText(client.displayName());
            tvIp.setText(client.ip);
            viewColor.setBackgroundColor(client.color);

            btnClearUser.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (client.name == null) return;
                    canvasView.removeOwner(client.name);
                    broadcast("CLEAR_USER|" + client.name);
                }
            });

            btnKick.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    kick(client);
                }
            });

            return convertView;
        }
    }

    // ------------------------------------------------------------------

    @Override
    protected void onDestroy() {
        super.onDestroy();
        running = false;
        ServerSocket ss = serverSocket;
        if (ss != null) {
            try { ss.close(); } catch (IOException ignored) {}
        }
        for (ClientHandler c : clients) {
            c.closeNow();
        }
        clients.clear();
    }
}