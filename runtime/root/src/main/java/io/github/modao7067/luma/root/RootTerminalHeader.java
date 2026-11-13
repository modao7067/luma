package io.github.modao7067.luma.root;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

final class RootTerminalHeader extends LinearLayout {
    final ImageButton back,keyboard,windows,settings;
    final TextView title;
    final StatusDot status;
    final ToolbarIcon windowsIcon;
    RootTerminalHeader(Context context,boolean managed){
        super(context);setGravity(Gravity.CENTER_VERTICAL);
        setPadding(dp(18),0,dp(8),0);setBackgroundColor(managed?Color.BLACK:0x0affffff);
        back=managed?button(new ToolbarIcon(ToolbarIcon.Kind.BACK)):null;
        if(back!=null){back.setContentDescription("返回会话");addView(back);}
        title=new TextView(context);title.setText("");title.setTextColor(0xffe4edf3);title.setTextSize(16f);
        title.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);title.setGravity(Gravity.CENTER_VERTICAL);
        if(managed)title.setIncludeFontPadding(false);
        LinearLayout group=new LinearLayout(context);group.setGravity(Gravity.CENTER_VERTICAL);
        if(managed)group.setBaselineAligned(false);
        group.addView(title,new LinearLayout.LayoutParams(-2,dp(48)));
        status=new StatusDot(context);group.addView(status,new LinearLayout.LayoutParams(dp(18),dp(48)));
        group.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->{
            int max=Math.max(1,r-l-dp(18));if(title.getMaxWidth()!=max)title.setMaxWidth(max);
        });
        addView(group,new LinearLayout.LayoutParams(0,dp(48),1f));
        keyboard=button(new ToolbarIcon(ToolbarIcon.Kind.KEYBOARD));keyboard.setContentDescription("显示或隐藏键盘");addView(keyboard);
        windowsIcon=new ToolbarIcon(ToolbarIcon.Kind.WINDOWS);windows=button(windowsIcon);
        windows.setContentDescription("多窗口：点击切换，长按新建");addView(windows);
        settings=managed?null:button(new ToolbarIcon(ToolbarIcon.Kind.MORE));
        if(settings!=null){settings.setContentDescription("更多选项");addView(settings);}
    }
    void setSessionLocked(boolean locked){
        windows.setVisibility(locked?View.GONE:View.VISIBLE);windows.setEnabled(!locked);
        title.setClickable(!locked);title.setLongClickable(!locked);
    }
    private int dp(float n){return LumaUi.dp(getContext(),n);}
    private ImageButton button(ToolbarIcon icon){
        ImageButton button=new ImageButton(getContext());button.setImageDrawable(icon);button.setScaleType(ImageView.ScaleType.FIT_CENTER);
        int pad=dp(10.5f);button.setPadding(pad,pad,pad,pad);button.setLayoutParams(new LinearLayout.LayoutParams(dp(45),dp(45)));
        button.setBackground(LumaUi.rippleSurface(Color.TRANSPARENT,0x22ffffff,dp(12),0,0));return button;
    }
}
