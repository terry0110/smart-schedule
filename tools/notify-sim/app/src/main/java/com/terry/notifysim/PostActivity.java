package com.terry.notifysim;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Person;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/**
 * 发一条通知，然后自己关掉。
 *
 * <pre>
 *   adb shell am start -n com.tencent.mm/com.terry.notifysim.PostActivity --ei case 3
 *   adb shell am start -n com.tencent.mm/com.terry.notifysim.PostActivity --ei case 3 --ez keep true
 * </pre>
 *
 * 加了 {@code --ez keep true} 就不自动关，方便在通知栏里手点。默认发完就 finish，
 * 批量跑的时候不会把屏幕挡住。
 *
 * 用原生 API 而不引 AndroidX：这个工具只需要能发通知，少一个依赖少一份出错的可能。
 */
public class PostActivity extends Activity {

    private static final String CHANNEL_ID = "sim";
    private static final String TAG = "notify-sim";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // --es body 优先：直接发一段指定文本，不用为了试一句话改代码重编
        String adhocBody = getIntent().getStringExtra("body");
        Cases.C c;
        int idx = getIntent().getIntExtra("case", 0);
        if (adhocBody != null && !adhocBody.isEmpty()) {
            String t = getIntent().getStringExtra("title");
            c = Cases.adhoc(t == null ? "临时发送" : t, adhocBody);
            idx = 0;
        } else {
            if (idx < 1 || idx > Cases.ALL.length) {
                Log.e(TAG, "case 超出范围 1.." + Cases.ALL.length);
                finish();
                return;
            }
            c = Cases.ALL[idx - 1];
        }

        NotificationManager nm = getSystemService(NotificationManager.class);
        ensureChannel(nm);

        Notification n = build(c);
        // id 用序号，避免几条互相覆盖；临时用例固定用一个号，反复发只覆盖一条
        nm.notify(1000 + idx, n);
        Log.i(TAG, "posted case=" + idx + " name=" + c.name);

        if (!getIntent().getBooleanExtra("keep", false)) {
            finish();
        }
    }

    private void ensureChannel(NotificationManager nm) {
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID, "模拟消息", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("用于验证智能日程的筛选规则");
        nm.createNotificationChannel(ch);
    }

    private Notification build(Cases.C c) {
        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.sym_action_chat)
                .setAutoCancel(true);

        if (c.messages != null) {
            // MessagingStyle：真实微信/QQ 群消息的形状。
            // 注意 style 会覆盖 EXTRA_TITLE，这正是真机上"标题变成会话名"的成因。
            Person me = new Person.Builder().setName("我").build();
            Notification.MessagingStyle style = new Notification.MessagingStyle(me);
            if (c.convTitle != null) style.setConversationTitle(c.convTitle);
            long now = System.currentTimeMillis();
            for (int i = 0; i < c.messages.length; i++) {
                String[] parts = c.messages[i].split("\\|", 2);
                String sender = parts[0];
                String body = parts.length > 1 ? parts[1] : "";
                Person p = new Person.Builder().setName(sender).build();
                style.addMessage(body, now + i * 1000L, p);
            }
            b.setStyle(style);
        } else {
            b.setContentTitle(c.title);
            if (c.text != null && !c.text.isEmpty()) b.setContentText(c.text);
        }

        return b.build();
    }
}
