package com.leeann323.fallalert;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends Activity {

    private EditText etServer;
    private EditText etTopic;
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
        tvUrl = (TextView) findViewById(R.id.tv_url);
        tvStatus = (TextView) findViewById(R.id.tv_status);
        lv = (ListView) findViewById(R.id.lv);

        etServer.setText(P.server(this));
        etTopic.setText(P.topic(this));
        refreshUrl();

        adapter = new AlertAdapter(this, new ArrayList<Alert>());
        lv.setAdapter(adapter);

        ((Button) findViewById(R.id.btn_start)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveConfig();
                startListening();
                Toast.makeText(MainActivity.this, "已开始监听", Toast.LENGTH_SHORT).show();
            }
        });

        ((Button) findViewById(R.id.btn_stop)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(MainActivity.this, AlertService.class).setAction(C.ACTION_STOP);
                startService(i);
                tvStatus.setText("已停止监听（重新点击“启动监听”可恢复）");
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

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != 0) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 9);
        }
    }

    private void saveConfig() {
        P.setServer(this, etServer.getText().toString());
        P.setTopic(this, etTopic.getText().toString());
        if (P.topic(this).isEmpty()) {
            P.setTopic(this, C.newTopic());
            etTopic.setText(P.topic(this));
        }
        refreshUrl();
    }

    private void refreshUrl() {
        tvUrl.setText("推送地址：" + P.fullUrl(this));
    }

    private void startListening() {
        Intent i = new Intent(this, AlertService.class).setAction(C.ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(i);
        } else {
            startService(i);
        }
    }

    /** 往同一个 topic 发一条测试告警，验证「电脑→中转→手机」整条链路 */
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

    private void reload() {
        List<Alert> list = P.history(this);
        adapter.setItems(list);
        lv.setSelection(0);
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
