package com.example.android_paint_client;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Client 端畫布。
 * 畫筆模式：觸控時「只把筆跡送給 Server」，真正畫上畫面的是 Server 廣播回來的 DRAW，
 *           這樣所有人（包含自己）看到的畫面一定一致。
 * 橡皮擦模式：手指碰到「自己畫的」某一筆，就通知 Server 把那一整筆刪掉；別人的筆跡碰不到。
 * 座標使用 0~1 的比例。所有方法都必須在 UI 執行緒呼叫。
 */
public class ClientCanvasView extends View {

    private static final float PEN_WIDTH = 8f;
    private static final float ERASE_RADIUS_DP = 20f; // 手指離線多近算「碰到」

    public interface OnDrawListener {
        void onDrawSend(int strokeId, float x1, float y1, float x2, float y2);
    }

    public interface OnEraseListener {
        void onErase(int strokeId);
    }

    private static class Segment {
        final String owner;
        final int strokeId;
        final float x1, y1, x2, y2;
        final int color;

        Segment(String owner, int strokeId, float x1, float y1, float x2, float y2, int color) {
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
    private final Set<Integer> erasedIds = new HashSet<>(); // 已送出刪除要求的筆畫，避免重複送

    private OnDrawListener onDrawListener;
    private OnEraseListener onEraseListener;
    private boolean eraser = false;
    private String myName;

    private int strokeCounter = 0;
    private int currentStrokeId = 0;
    private boolean drawing = false;
    private float lastX, lastY; // 0~1 比例座標

    public ClientCanvasView(Context context, AttributeSet attrs) {
        super(context, attrs);
        paint.setAntiAlias(true);
        paint.setStrokeWidth(PEN_WIDTH);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
    }

    public void setOnDrawListener(OnDrawListener listener) {
        this.onDrawListener = listener;
    }

    public void setOnEraseListener(OnEraseListener listener) {
        this.onEraseListener = listener;
    }

    public void setEraser(boolean eraser) {
        this.eraser = eraser;
        this.drawing = false;
    }

    /** 自己的名字（以 Server 在 WELCOME 回傳的為準），橡皮擦只會刪這個名字的筆畫 */
    public void setMyName(String name) {
        this.myName = name;
    }

    // ---- 以下由「收到 Server 訊息」呼叫 ----

    public void addSegment(String owner, int strokeId, float x1, float y1, float x2, float y2, int color) {
        segments.add(new Segment(owner, strokeId, x1, y1, x2, y2, color));
        invalidate();
    }

    public void removeStroke(final String owner, final int strokeId) {
        segments.removeIf(s -> s.owner.equals(owner) && s.strokeId == strokeId);
        invalidate();
    }

    public void removeOwner(final String owner) {
        segments.removeIf(s -> s.owner.equals(owner));
        invalidate();
    }

    public void clearAll() {
        segments.clear();
        invalidate();
    }

    // ---- 觸控 ----

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return false;

        int action = event.getActionMasked();

        if (eraser) {
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_MOVE) {
                int hit = findOwnStrokeAt(event.getX(), event.getY());
                if (hit != -1) {
                    erasedIds.add(hit);
                    if (onEraseListener != null) {
                        onEraseListener.onErase(hit);
                    }
                }
            } else if (action == MotionEvent.ACTION_UP) {
                performClick();
            }
            return true;
        }

        float x = clamp01(event.getX() / w);
        float y = clamp01(event.getY() / h);

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                currentStrokeId = ++strokeCounter;
                drawing = true;
                lastX = x;
                lastY = y;
                fireDraw(x, y, x, y); // 單點也能留下一個點
                break;
            case MotionEvent.ACTION_MOVE:
                if (drawing) {
                    fireDraw(lastX, lastY, x, y);
                    lastX = x;
                    lastY = y;
                }
                break;
            case MotionEvent.ACTION_UP:
                drawing = false;
                performClick();
                break;
            case MotionEvent.ACTION_CANCEL:
                drawing = false;
                break;
            default:
                break;
        }
        return true;
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    /** 找出手指位置附近「自己的」筆畫，回傳 strokeId；沒有碰到回傳 -1 */
    private int findOwnStrokeAt(float px, float py) {
        if (myName == null) return -1;
        float w = getWidth();
        float h = getHeight();
        float threshold = ERASE_RADIUS_DP * getResources().getDisplayMetrics().density;

        for (int i = segments.size() - 1; i >= 0; i--) {
            Segment s = segments.get(i);
            if (!s.owner.equals(myName) || erasedIds.contains(s.strokeId)) continue;
            if (distanceToSegment(px, py, s.x1 * w, s.y1 * h, s.x2 * w, s.y2 * h) <= threshold) {
                return s.strokeId;
            }
        }
        return -1;
    }

    /** 點 (px,py) 到線段 (x1,y1)-(x2,y2) 的最短距離（像素） */
    private static float distanceToSegment(float px, float py, float x1, float y1, float x2, float y2) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float lenSq = dx * dx + dy * dy;
        float t = 0f;
        if (lenSq > 0f) {
            t = ((px - x1) * dx + (py - y1) * dy) / lenSq;
            t = Math.max(0f, Math.min(1f, t));
        }
        float cx = x1 + t * dx;
        float cy = y1 + t * dy;
        float ex = px - cx;
        float ey = py - cy;
        return (float) Math.sqrt(ex * ex + ey * ey);
    }

    private void fireDraw(float x1, float y1, float x2, float y2) {
        if (onDrawListener != null) {
            onDrawListener.onDrawSend(currentStrokeId, x1, y1, x2, y2);
        }
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
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