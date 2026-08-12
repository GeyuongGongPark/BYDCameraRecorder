package com.ggpark.byddashcam;

import android.content.Context;
import android.view.View;
import android.widget.GridLayout;

public final class AspectGridLayout extends GridLayout {
    private final int heightUnits;
    private final int widthUnits;

    public AspectGridLayout(
            Context context,
            int widthUnits,
            int heightUnits) {
        super(context);
        this.widthUnits = Math.max(1, widthUnits);
        this.heightUnits = Math.max(1, heightUnits);
    }

    @Override
    protected void onMeasure(
            int widthMeasureSpec,
            int heightMeasureSpec) {
        int measuredWidth = View.MeasureSpec.getSize(widthMeasureSpec);
        int aspectHeight =
                measuredWidth * heightUnits / widthUnits;
        super.onMeasure(
                widthMeasureSpec,
                View.MeasureSpec.makeMeasureSpec(
                        aspectHeight,
                        View.MeasureSpec.EXACTLY));
    }
}
