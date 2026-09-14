package io.github.parkjaehyeon12354.zakocode;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 명령을 Termux 에 보내 실행하고 결과를 받는다 — Termux 의 RUN_COMMAND 인텐트.
 * https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent
 * 필요한 것: Termux 설치 후 한 번 열기, ~/.termux/termux.properties 에 allow-external-apps=true, 이 앱에 RUN_COMMAND 권한.
 */
final class Termux implements Agent.Shell {
    static final String PACKAGE = "com.termux";
    static final String PERMISSION = "com.termux.permission.RUN_COMMAND";
    private static final String BASH = "/data/data/com.termux/files/usr/bin/bash";
    private static final String HOME = "/data/data/com.termux/files/home";

    private final Context context;

    Termux(Context context) {
        this.context = context.getApplicationContext();
    }

    static boolean installed(Context context) {
        try {
            context.getPackageManager().getPackageInfo(PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    @Override
    public Agent.Result run(String command) throws IOException, InterruptedException {
        if (!installed(context)) {
            throw new IOException("Termux 가 설치돼 있지 않습니다. F-Droid 에서 Termux 를 설치하고 한 번 열어 주세요.");
        }
        String action = context.getPackageName() + ".RESULT." + UUID.randomUUID();
        BlockingQueue<Bundle> box = new ArrayBlockingQueue<>(1);
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent intent) {
                Bundle result = intent.getBundleExtra("result");
                box.offer(result != null ? result : new Bundle());
            }
        };
        if (Build.VERSION.SDK_INT >= 33) {
            context.registerReceiver(receiver, new IntentFilter(action), Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(receiver, new IntentFilter(action));
        }
        try {
            // Termux 가 결과를 채워 보내므로 수정 가능(MUTABLE)이어야 하고, 안드로이드 14 는 그런 인텐트에 대상 패키지를 요구한다
            Intent back = new Intent(action).setPackage(context.getPackageName());
            PendingIntent pending = PendingIntent.getBroadcast(context, 0, back,
                    PendingIntent.FLAG_ONE_SHOT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0));
            Intent run = new Intent("com.termux.RUN_COMMAND")
                    .setClassName(PACKAGE, "com.termux.app.RunCommandService")
                    .putExtra("com.termux.RUN_COMMAND_PATH", BASH)
                    .putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{"-c", command})
                    .putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME)
                    .putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
                    .putExtra("com.termux.RUN_COMMAND_COMMAND_LABEL", "Zako Code")
                    .putExtra("com.termux.RUN_COMMAND_PENDING_INTENT", pending);
            ComponentName started;
            try {
                started = context.startForegroundService(run);
            } catch (SecurityException e) {
                throw new IOException("Termux 명령 실행 권한이 없습니다. 설정 → 애플리케이션 → Zako Code → 권한에서 허용해 주세요.");
            } catch (IllegalStateException e) {
                throw new IOException("Termux 를 깨우지 못했습니다: " + e.getMessage());
            }
            if (started == null) throw new IOException("Termux 에 명령을 보내지 못했습니다. Termux 를 한 번 열어 주세요.");

            // ponytail: 끝날 때까지 기다린다(최대 10분). 중단해도 Termux 쪽 명령은 계속 돈다 — 멈추려면 Termux 알림에서 끌 것
            Bundle result = box.poll(10, TimeUnit.MINUTES);
            if (result == null) return new Agent.Result(null, "(10분 안에 Termux 가 답하지 않았습니다)\n");
            if (!result.containsKey("exitCode")) {   // 실행 전에 막혔다 — allow-external-apps 가 없을 때 등
                throw new IOException("Termux 가 명령을 거절했습니다: " + result.getString("errmsg", "이유를 알 수 없음"));
            }
            return new Agent.Result(result.getInt("exitCode"), result.getString("stdout", "") + result.getString("stderr", ""));
        } finally {
            context.unregisterReceiver(receiver);
        }
    }
}
