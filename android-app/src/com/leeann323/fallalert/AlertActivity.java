package com.leeann323.fallalert;

import android.app.Activity;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

/** 全屏告警页：锁屏点亮、直接盖在最上层，适合夜间无人值守场景 */
public class AlertActivity extends Activity {

    private TextView tvTitle;
    private TextView tvBody;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_alert);
        tvTitle = (TextView) findViewById(R.id.tv_alert_title);
        tvBody = (TextView) findViewById(R.id.tv_alert_body);

        ((Button) findViewById(R.id.btn_ack)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) nm.cancel(C.NOTI_ALERT);
                finish();
            }
        });

        ((Button) findViewById(R.id.btn_detail)).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
                if (nm != null) nm.cancel(C.NOTI_ALERT);
                startActivity(new Intent(AlertActivity.this, MainActivity.class));
                finish();
            }
        });

        bind(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        bind(intent);
    }

    private void bind(Intent i) {
        String title = i == null ? "跌倒告警" : i.getStringExtra("title");
        String body = i == null ? "" : i.getStringExtra("body");
        tvTitle.setText(title == null || title.isEmpty() ? "跌倒告警" : title);
        tvBody.setText(body == null ? "" : body);
    }
}
