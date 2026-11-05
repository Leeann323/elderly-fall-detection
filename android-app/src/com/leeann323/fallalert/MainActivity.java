package com.leeann323.fallalert;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private EditText etServer;
    private EditText etTopic;
    private TextView tvEffective;
    private TextView tvUrl;
    private TextView tvStatus;
    private ListView lv;
    private AlertAdapter adapter;
    private boolean registered = false;

    private final BroadcastReceiver rx = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            reload();
            if (tvStatus != null) {
                tvStatus.setText("刚刚收到告警：" + intent.getStringExtra("kind_name")
                        + " " + intent.getStringExtra("area"));
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        etServer = (EditText) findViewById(R.id.et_server);
        etTopic = (EditText) findViewById(R.id.et_topic);
        tvEffective = (TextView) findViewById(R.id.tv_effective);
        tvUrl = (TextView) findViewById(R.id.tv_url);
        tvStatus = (TextView) findViewById(R.id.tv_status);
        lv = (ListView) findViewById(R.id.lv);

        etServer.setText(P.server(this));
        etTopic.setText(P.topic(this));

        adapter = new AlertAdapter(this, new ArrayList<Alert>());
        lv.setAdapter(adapter);

        lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Alert a = (Alert) adapter.getItem(position);
                if (a == null) return;
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle(a.title())
                        .setMessage(a.detail())
                        .setPositiveButton("关闭", null)
                        .setNeutralButton("复制", new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int w) {
                                // no-op，仅用于占位
                            }
                        })
                        .show();
            }
        });

        ((Button) findViewById(R.id.btn_start)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveConfig();
                startListening();
                reload();
                Toast.makeText(MainActivity.this, "已开始监听：" + P.topic(MainActivity.this),
                        Toast.LENGTH_SHORT).show();
            }
        });

        ((Button) findViewById(R.id.btn_stop)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startService(new Intent(MainActivity.this, AlertService.class).setAction(C.ACTION_STOP));
                reload();
            }
        });

        ((Button) findViewById(R.id.btn_test)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveConfig();
                sendTest();
            }
        });

        ((Button) findViewById(R.id.btn_clear)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                P.clearHistory(MainActivity.this);
                reload();
            }
        });

        ((Button) findViewById(R.id.btn_diag)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                diagnose();
            }
        });

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != 0) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 9);
        }

        // 首次启动：强调 Topic 必须与电脑端一致（这是最常见的"收不到"原因）
        if (!P.seenIntro(this)) {
            P.setSeenIntro(this, true);
            new AlertDialog.Builder(this)
                    .setTitle("先对齐 Topic 再监听")
                    .setMessage("电脑端 D:\\English_Road\\elderly-fall-detection\\ntfy_topic.txt "
                            + "里有一行 Topic，必须与这里的「频道 Topic」完全一致，"
                            + "否则手机收不到任何告警。\n\n当前手机端 Topic：\n" + P.topic(this)
                            + "\n\n启动监听后如果仍无通知，点最下方的「诊断」按钮，它会直接告诉你问题在哪。")
                    .setPositiveButton("复制当前 Topic", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            copy(P.topic(MainActivity.this), "Topic 已复制，可粘到电脑端 ntfy_topic.txt");
                        }
                    })
                    .setNegativeButton("知道了", null)
                    .show();
        }
    }

    private void saveConfig() {
        P.setServer(this, etServer.getText().toString());
        P.setTopic(this, etTopic.getText().toString());
        if (P.topic(this).isEmpty()) {
            P.setTopic(this, C.newTopic());
        }
        etTopic.setText(P.topic(this));
        etServer.setText(P.server(this));
        reload();
    }

    private void refreshLabels() {
        String full = P.fullUrl(this);
        tvUrl.setText("推送地址：" + full + "/json?poll=1&since=…");
        boolean running = P.running(this);
        tvEffective.setText((running ? "● 正在监听： " : "○ 未监听： ") + P.topic(this));
        tvEffective.setTextColor(running ? 0xFFB71C1C : 0xFF666666);
    }

    private void startListening() {
        Intent i = new Intent(this, AlertService.class).setAction(C.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(i);
        } else {
            startService(i);
        }
    }

    /** 往同一个 topic 发一条测试告警，验证「手机→中转」上行是否通 */
    private void sendTest() {
        final String url = P.fullUrl(this);
        final String body = "{\"kind\":\"A\",\"kind_name\":\"测试告警\",\"area\":\"客厅\","
                + "\"eid\":\"TEST-" + System.currentTimeMillis() % 100000 + "\","
                + "\"time\":\"" + new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss",
                java.util.Locale.US).format(new java.util.Date()) + "\","
                + "\"hold\":8.0,\"score\":0.99,\"note\":\"来自手机自测\"}";
        tvStatus.setText("正在发送测试告警…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String err = null;
                try {
                    Net.post(url, body, "application/json; charset=utf-8");
                } catch (Exception e) {
                    err = e.getMessage();
                }
                final String msg = err == null ? "已发送，2~10 秒内应收到通知"
                        : ("发送失败：" + Net.shorten(err));
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        tvStatus.setText(msg);
                    }
                });
            }
        }, "test-send").start();
    }

    /** 一次性拉取诊断：把"为什么收不到"直接写在弹窗里 */
    private void diagnose() {
        saveConfig();
        final String base = P.fullUrl(this);
        tvStatus.setText("诊断中…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                StringBuilder sb = new StringBuilder();
                sb.append("当前监听 Topic：").append(P.topic(MainActivity.this)).append('\n');
                sb.append("服务状态：").append(P.running(MainActivity.this) ? "已启动" : "未启动").append('\n');
                sb.append("请求：").append(base).append("/json?poll=1&since=24h\n\n");
                try {
                    String bodyText = Net.get(base + "/json?poll=1&since=24h");
                    int n = 0;
                    String latest = "";
                    String[] lines = bodyText.split("\n");
                    for (int i = 0; i < lines.length; i++) {
                        String line = lines[i].trim();
                        if (line.isEmpty()) continue;
                        JSONObject o;
                        try {
                            o = new JSONObject(line);
                        } catch (Exception e) {
                            continue;
                        }
                        if (!"message".equals(o.optString("event", "message"))) continue;
                        n++;
                        latest = o.optString("message", "");
                    }
                    sb.append("HTTP：200 OK\n近 24 小时消息数：").append(n).append('\n');
                    if (n > 0) {
                        if (latest.length() > 160) latest = latest.substring(0, 160) + "…";
                        sb.append("最新一条：").append(latest).append("\n\n");
                        sb.append("✅ 消息能拉到 → 网络与 Topic 都正确。\n");
                        sb.append("若仍无通知：检查通知权限、告警通道是否被静音、是否被系统限制后台。");
                    } else {
                        sb.append("\n⚠️ 拉取成功但 0 条消息：\n");
                        sb.append("· 电脑端若显示“已推送”，说明两边 Topic 不一致 —— ")
                          .append("请拿电脑端 ntfy_topic.txt 的内容逐字核对（区分大小写、不要有空格）\n");
                        sb.append("· 电脑端若未显示“已推送”，请检查它是否打印了"
                                + "“[info] 手机远程推送：已启用”");
                    }
                } catch (Exception e) {
                    sb.append("❌ 请求失败：").append(Net.shorten(e.getMessage())).append("\n\n");
                    sb.append("→ 手机到中转服务的网络不通：\n");
                    sb.append("· 换 Wi-Fi / 4G 再试一次\n");
                    sb.append("· 用手机浏览器打开 ").append(base).append(" 看能否访问\n");
                    sb.append("· 若换网络仍失败，可在「中转服务」里改用自建 ntfy 地址");
                }
                final String report = sb.toString();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        tvStatus.setText("诊断完成");
                        new AlertDialog.Builder(MainActivity.this)
                                .setTitle("诊断结果")
                                .setMessage(report)
                                .setPositiveButton("知道了", null)
                                .setNeutralButton("复制", new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface d, int w) {
                                        copy(report, "诊断结果已复制");
                                    }
                                })
                                .show();
                    }
                });
            }
        }, "diag").start();
    }

    private void copy(String text, String tip) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("fallalert", text));
        }
        Toast.makeText(this, tip, Toast.LENGTH_LONG).show();
    }

    private void reload() {
        List<Alert> list = P.history(this);
        adapter.setItems(list);
        if (!list.isEmpty()) {
            lv.setSelection(0);
        }
        refreshLabels();
        if (P.running(this)) {
            tvStatus.setText("监听中 · 已记录 " + list.size() + " 条");
        } else {
            tvStatus.setText("未启动 · 本地已存 " + list.size() + " 条记录");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter f = new IntentFilter(C.ACTION_NEW_ALERT);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(rx, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(rx, f);
        }
        registered = true;
        reload();
        if (P.running(this) && Build.VERSION.SDK_INT >= 26) {
            startForegroundService(new Intent(this, AlertService.class).setAction(C.ACTION_START));
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (registered) {
            try {
                unregisterReceiver(rx);
            } catch (Exception ignored) {
            }
            registered = false;
        }
    }
}
