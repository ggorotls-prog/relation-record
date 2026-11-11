package app.relrecord;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.telephony.TelephonyManager;

// 전화 상태가 바뀔 때(울림·통화 중·끝남). 끝나면(IDLE) 통화 기록을 읽는 작업을 예약한다.
// 번호는 여기서 읽지 않는다(통화 기록에서 읽음).
public class CallReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context ctx, Intent intent) {
        if (!TelephonyManager.ACTION_PHONE_STATE_CHANGED.equals(intent.getAction())) return;
        if (TelephonyManager.EXTRA_STATE_IDLE.equals(intent.getStringExtra(TelephonyManager.EXTRA_STATE))) CallJob.soon(ctx);
    }
}
