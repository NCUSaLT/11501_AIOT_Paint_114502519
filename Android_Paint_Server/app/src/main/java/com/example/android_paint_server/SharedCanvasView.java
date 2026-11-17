package com.example.android_paint_server;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

/**
 * Server 端的共用畫布，同時也是「筆跡歷史」的唯一儲存處。
 * 座標一律使用 0~1 的比例，不同螢幕大小的裝置才會對得上。
 * 所有方法都必須在 UI 執行緒呼叫。
 */
public class SharedCanvasView extends View {

    private static final float PEN_WIDTH = 8f;

    public static class Segment {
        public final String owner;
        public final int strokeId;
        public final float x1, y1, x2, y2; // 0~1 比例座標
        public final int color;

        public Segment(String owner, int strokeId, float x1, float y1, float x2, float y2, int color) {
            this.owner = owner;
            this.strokeId = strokeId;
            this.x1 = x1;
            this.y1 = y1;
            this.x2 = x2;
            this.y2 = y2;
            this.color = color;
        }
    }

    private final Paint paint = new Paint();
    private final List<Segment> segments = new ArrayList<>();

    public SharedCanvasView(Context context, AttributeSet attrs) {
        super(context, attrs);
        paint.setAntiAlias(true);
        paint.setStrokeWidth(PEN_WIDTH);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
    }

    /** 回傳目前所有筆跡（給 Server 在新 Client 加入時同步用，只可讀取） */
    public List<Segment> getSegments() {
        return segments;
    }

    public void addSegment(Segment segment) {
        segments.add(segment);
        invalidate();
    }

    /** 移除某人的某一筆完整筆畫；有找到並移除才回傳 true */
    public boolean removeStroke(final String owner, final int strokeId) {
        boolean removed = segments.removeIf(s -> s.owner.equals(owner) && s.strokeId == strokeId);
        if (removed) invalidate();
        return removed;
    }

    /** 移除某人所有筆跡 */
    public void removeOwner(final String owner) {
        segments.removeIf(s -> s.owner.equals(owner));
        invalidate();
    }

    public void clearAll() {
        segments.clear();
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float h = getHeight();
        for (Segment s : segments) {
            paint.setColor(s.color);
            canvas.drawLine(s.x1 * w, s.y1 * h, s.x2 * w, s.y2 * h, paint);
        }
    }
}