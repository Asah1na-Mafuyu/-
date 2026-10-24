package com.jijian.translate;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

final class RegionView extends View {
    private Bitmap bitmap;
    private final RectF displayed = new RectF(), selection = new RectF();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private float downX, downY; private boolean selecting;
    RegionView(Context context, Bitmap bitmap) { super(context); this.bitmap = bitmap; setContentDescription("拖动框选要翻译的区域"); }
    void rotate() { bitmap = ImageTools.rotate(bitmap); selection.setEmpty(); requestLayout(); invalidate(); }
    void reset() { selection.set(displayed); invalidate(); }
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) { selection.setEmpty(); }
    private void fit() {
        float scale = Math.min(getWidth()/(float)bitmap.getWidth(),getHeight()/(float)bitmap.getHeight());
        float width = bitmap.getWidth()*scale, height=bitmap.getHeight()*scale;
        displayed.set((getWidth()-width)/2,(getHeight()-height)/2,(getWidth()+width)/2,(getHeight()+height)/2);
        if (selection.isEmpty()) selection.set(displayed);
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas); if (getWidth()==0 || getHeight()==0) return; fit(); canvas.drawColor(Color.rgb(20,22,28));
        paint.setColor(Color.WHITE); canvas.drawBitmap(bitmap,null,displayed,paint);
        Path mask = new Path(); mask.setFillType(Path.FillType.EVEN_ODD); mask.addRect(displayed,Path.Direction.CW); mask.addRect(selection,Path.Direction.CW);
        paint.setColor(0x99000000); paint.setStyle(Paint.Style.FILL); canvas.drawPath(mask,paint);
        paint.setColor(Color.rgb(146,179,255)); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(Ui.dp(getContext(),2)); canvas.drawRect(selection,paint); paint.setStyle(Paint.Style.FILL);
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        float x=Math.max(displayed.left,Math.min(displayed.right,event.getX())), y=Math.max(displayed.top,Math.min(displayed.bottom,event.getY()));
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (!displayed.contains(event.getX(),event.getY())) return false;
                selecting=true; downX=x; downY=y; selection.set(x,y,x+1,y+1); getParent().requestDisallowInterceptTouchEvent(true); return true;
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_UP:
                if (!selecting) return false; selection.set(Math.min(downX,x),Math.min(downY,y),Math.max(downX,x),Math.max(downY,y));
                if (event.getActionMasked()==MotionEvent.ACTION_UP) { selecting=false; if(selection.width()<Ui.dp(getContext(),8)||selection.height()<Ui.dp(getContext(),8)) selection.set(displayed); performClick(); }
                invalidate(); return true;
            case MotionEvent.ACTION_CANCEL: selecting=false; selection.set(displayed); invalidate(); return true;
            default: return true;
        }
    }
    @Override public boolean performClick() { super.performClick(); return true; }
    static Rect toBitmap(RectF selected, RectF displayed, int width, int height) {
        int left=Math.max(0,Math.min(width-1,(int)Math.floor((selected.left-displayed.left)*width/displayed.width())));
        int top=Math.max(0,Math.min(height-1,(int)Math.floor((selected.top-displayed.top)*height/displayed.height())));
        int right=Math.max(left+1,Math.min(width,(int)Math.ceil((selected.right-displayed.left)*width/displayed.width())));
        int bottom=Math.max(top+1,Math.min(height,(int)Math.ceil((selected.bottom-displayed.top)*height/displayed.height())));
        return new Rect(left,top,right,bottom);
    }
    Bitmap selectedBitmap() {
        fit(); Rect rect=toBitmap(selection,displayed,bitmap.getWidth(),bitmap.getHeight());
        return Bitmap.createBitmap(bitmap,rect.left,rect.top,rect.width(),rect.height());
    }
}
