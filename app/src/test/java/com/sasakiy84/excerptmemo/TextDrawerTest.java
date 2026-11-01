package com.sasakiy84.excerptmemo;

import static com.google.common.truth.Truth.assertThat;
import android.Manifest;
import android.view.*;
import androidx.lifecycle.ViewModelProvider;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=36)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class TextDrawerTest {
    @Test public void handleContinuouslyResizesDrawerAndKeepsUserHeightDuringSelectionChanges() throws Exception {
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(Manifest.permission.CAMERA);
        var controller = Robolectric.buildActivity(MainActivity.class).setup();
        try {
            var activity = controller.get();
            var model = new ViewModelProvider(activity).get(DocumentViewModel.class);
            DocumentExtractionHostTest.awaitIdle(model); model.closePage().get();
            model.page = android.graphics.Bitmap.createBitmap(1200,1700,android.graphics.Bitmap.Config.ARGB_8888);
            model.allLines = java.util.List.of(new OcrLine(80,100,1000,160,"選択した文章をここに表示する"));
            model.setInclusions(false,false,false);
            View root = activity.findViewById(R.id.root);
            layout(root);
            TextDrawer drawer = activity.findViewById(R.id.text_drawer);
            int before = drawer.getHeight();
            View handle = activity.findViewById(R.id.drawer_handle);
            send(handle,0,400); send(handle,2,200); send(handle,1,200); layout(root);
            assertThat(drawer.getHeight()).isEqualTo(before+200);
            float fraction = model.textPanelFraction;
            model.select(0,5); layout(root);
            assertThat(model.textPanelFraction).isEqualTo(fraction);
            assertThat(drawer.getHeight()).isEqualTo(before+200);
            send(handle,0,200); send(handle,2,300); send(handle,1,300); layout(root);
            assertThat(drawer.getHeight()).isEqualTo(before+100);
        } finally { DocumentExtractionHostTest.close(controller); }
    }
    private static void layout(View root) {
        for(int i=0;i<3;i++) { root.measure(View.MeasureSpec.makeMeasureSpec(1080,1073741824),View.MeasureSpec.makeMeasureSpec(1920,1073741824)); root.layout(0,0,1080,1920); }
    }
    private static void send(View view,int action,float y) {
        MotionEvent event=MotionEvent.obtain(0,20,action,20,y,0); view.dispatchTouchEvent(event); event.recycle();
    }
}
